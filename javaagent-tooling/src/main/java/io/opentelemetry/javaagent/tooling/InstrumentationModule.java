/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import static com.google.common.base.Preconditions.checkArgument;
import static io.opentelemetry.javaagent.tooling.bytebuddy.matcher.AgentElementMatchers.failSafe;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

import io.opentelemetry.instrumentation.api.config.Config;
import io.opentelemetry.javaagent.tooling.bytebuddy.AgentTransformers;
import io.opentelemetry.javaagent.tooling.bytebuddy.ExceptionHandlers;
import io.opentelemetry.javaagent.tooling.context.FieldBackedProvider;
import io.opentelemetry.javaagent.tooling.context.InstrumentationContextProvider;
import io.opentelemetry.javaagent.tooling.context.NoopContextProvider;
import io.opentelemetry.javaagent.tooling.muzzle.matcher.Mismatch;
import io.opentelemetry.javaagent.tooling.muzzle.matcher.ReferenceMatcher;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instrumentation module groups several connected {@link TypeInstrumentation}s together, sharing
 * classloader matcher, helper classes, muzzle safety checks, etc. Ideally all types in a single
 * instrumented library should live in a single module.
 */
public abstract class InstrumentationModule {
  private static final Logger log = LoggerFactory.getLogger(InstrumentationModule.class);

  private static final String[] EMPTY = new String[0];

  // Added here instead of AgentInstaller's ignores because it's relatively
  // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
  public static final ElementMatcher.Junction<AnnotationSource> NOT_DECORATOR_MATCHER =
      not(isAnnotatedWith(named("javax.decorator.Decorator")));

  private final Set<String> instrumentationNames;
  protected final boolean enabled;

  protected final String packageName =
      getClass().getPackage() == null ? "" : getClass().getPackage().getName();

  public InstrumentationModule(
      String mainInstrumentationName, String... otherInstrumentationNames) {
    this(toList(mainInstrumentationName, otherInstrumentationNames));
  }

  private static List<String> toList(String first, String[] rest) {
    List<String> instrumentationNames = new ArrayList<>(rest.length + 1);
    instrumentationNames.add(first);
    instrumentationNames.addAll(asList(rest));
    return instrumentationNames;
  }

  public InstrumentationModule(List<String> instrumentationNames) {
    checkArgument(instrumentationNames.size() > 0, "InstrumentationModules must be named");
    this.instrumentationNames = new LinkedHashSet<>(instrumentationNames);
    enabled = Config.get().isInstrumentationEnabled(this.instrumentationNames, defaultEnabled());
  }

  /**
   * Add this instrumentation to an AgentBuilder.
   *
   * @param parentAgentBuilder AgentBuilder to base instrumentation config off of.
   * @return the original agentBuilder and this instrumentation
   */
  public final AgentBuilder instrument(AgentBuilder parentAgentBuilder) {
    if (!enabled) {
      log.debug("Instrumentation {} is disabled", instrumentationNames.iterator().next());
      return parentAgentBuilder;
    }

    ElementMatcher.Junction<ClassLoader> moduleClassLoaderMatcher = classLoaderMatcher();
    MuzzleMatcher muzzleMatcher = new MuzzleMatcher();
    HelperInjector helperInjector =
        new HelperInjector(
            getClass().getSimpleName(), asList(helperClassNames()), asList(helperResourceNames()));
    InstrumentationContextProvider contextProvider = getContextProvider();

    AgentBuilder agentBuilder = parentAgentBuilder;
    for (TypeInstrumentation typeInstrumentation : typeInstrumentations()) {
      AgentBuilder.Identified.Extendable extendableAgentBuilder =
          agentBuilder
              .type(
                  failSafe(
                      typeInstrumentation.typeMatcher(),
                      "Instrumentation type matcher unexpected exception: " + getClass().getName()),
                  failSafe(
                      moduleClassLoaderMatcher.and(typeInstrumentation.classLoaderMatcher()),
                      "Instrumentation class loader matcher unexpected exception: "
                          + getClass().getName()))
              .and(NOT_DECORATOR_MATCHER)
              .and(muzzleMatcher)
              .transform(AgentTransformers.defaultTransformers())
              .transform(helperInjector);
      extendableAgentBuilder = contextProvider.instrumentationTransformer(extendableAgentBuilder);
      extendableAgentBuilder =
          applyInstrumentationTransformers(
              typeInstrumentation.transformers(), extendableAgentBuilder);
      extendableAgentBuilder = contextProvider.additionalInstrumentation(extendableAgentBuilder);

      agentBuilder = extendableAgentBuilder;
    }

    return agentBuilder;
  }

  private AgentBuilder.Identified.Extendable applyInstrumentationTransformers(
      Map<? extends ElementMatcher<? super MethodDescription>, String> transformers,
      AgentBuilder.Identified.Extendable agentBuilder) {
    for (Map.Entry<? extends ElementMatcher<? super MethodDescription>, String> entry :
        transformers.entrySet()) {
      agentBuilder =
          agentBuilder.transform(
              new AgentBuilder.Transformer.ForAdvice()
                  .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
                  .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                  .advice(entry.getKey(), entry.getValue()));
    }
    return agentBuilder;
  }

  private InstrumentationContextProvider getContextProvider() {
    Map<String, String> contextStore = contextStore();
    if (!contextStore.isEmpty()) {
      return new FieldBackedProvider(getClass(), contextStore);
    } else {
      return NoopContextProvider.INSTANCE;
    }
  }

  /**
   * A ByteBuddy matcher that decides whether this instrumentation should be applied. Calls
   * generated {@link ReferenceMatcher}: if any mismatch with the passed {@code classLoader} is
   * found this instrumentation is skipped.
   */
  private class MuzzleMatcher implements AgentBuilder.RawMatcher {
    @Override
    public boolean matches(
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain) {
      /* Optimization: calling getMuzzleReferenceMatcher() inside this method
       * prevents unnecessary loading of muzzle references during agentBuilder
       * setup.
       */
      ReferenceMatcher muzzle = getMuzzleReferenceMatcher();
      if (muzzle != null) {
        boolean isMatch = muzzle.matches(classLoader);

        if (log.isDebugEnabled()) {
          if (!isMatch) {
            log.debug(
                "Instrumentation skipped, mismatched references were found: {} -- {} on {}",
                instrumentationNames.iterator().next(),
                InstrumentationModule.this.getClass().getName(),
                classLoader);
            List<Mismatch> mismatches = muzzle.getMismatchedReferenceSources(classLoader);
            for (Mismatch mismatch : mismatches) {
              log.debug("-- {}", mismatch);
            }
          } else {
            log.debug(
                "Applying instrumentation: {} -- {} on {}",
                instrumentationNames.iterator().next(),
                InstrumentationModule.this.getClass().getName(),
                classLoader);
          }
        }

        return isMatch;
      }
      return true;
    }
  }

  /**
   * The actual implementation of this method is generated automatically during compilation by the
   * {@link io.opentelemetry.javaagent.tooling.muzzle.collector.MuzzleCodeGenerationPlugin}
   * ByteBuddy plugin.
   *
   * <p><b>This method is generated automatically, do not override it.</b>
   */
  protected ReferenceMatcher getMuzzleReferenceMatcher() {
    return null;
  }

  /**
   * Order of adding instrumentation to ByteBuddy. For example instrumentation with order 1 runs
   * after an instrumentation with order 0 (default) matched on the same API.
   *
   * @return the order of adding an instrumentation to ByteBuddy. Default value is 0 - no order.
   */
  public int getOrder() {
    return 0;
  }

  /** @return Class names of helpers to inject into the user's classloader */
  public String[] helperClassNames() {
    return EMPTY;
  }

  /** @return Resource names to inject into the user's classloader */
  public String[] helperResourceNames() {
    return EMPTY;
  }

  /**
   * An instrumentation module can implement this method to make sure that the classloader contains
   * the particular library version. It is useful to implement that if the muzzle check does not
   * fail for versions out of the instrumentation's scope.
   *
   * <p>E.g. supposing version 1.0 has class {@code A}, but it was removed in version 2.0; A is not
   * used in the helper classes at all; this module is instrumenting 2.0: this method will return
   * {@code not(hasClassesNamed("A"))}.
   *
   * @return A type matcher used to match the classloader under transform
   */
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return any();
  }

  /** @return A list of all individual type instrumentation in this module. */
  public abstract List<TypeInstrumentation> typeInstrumentations();

  /**
   * Context stores to define for this instrumentation.
   *
   * <p>A map of {@code class-name to context-class-name}. Keys (and their subclasses) will be
   * associated with a context of the value.
   */
  protected Map<String, String> contextStore() {
    return Collections.emptyMap();
  }

  protected boolean defaultEnabled() {
    return Config.get().getBooleanProperty("otel.instrumentations.enabled", true);
  }
}
