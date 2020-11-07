/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.muzzle.collector;

import io.opentelemetry.javaagent.tooling.InstrumentationModule;
import io.opentelemetry.javaagent.tooling.Instrumenter;
import io.opentelemetry.javaagent.tooling.Instrumenter.Default;
import io.opentelemetry.javaagent.tooling.Utils;
import io.opentelemetry.javaagent.tooling.muzzle.Reference;
import io.opentelemetry.javaagent.tooling.muzzle.matcher.ReferenceMatcher;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

/**
 * This class generates the actual implementation of the {@link Default#getMuzzleReferenceMatcher()}
 * method. It collects references from all advice classes defined in an instrumentation and writes
 * them as Java bytecode in the generated {@link Default#getMuzzleReferenceMatcher()} method.
 *
 * <p>This class is run at compile time by the {@link MuzzleCodeGenerationPlugin} ByteBuddy plugin.
 */
class MuzzleCodeGenerator implements AsmVisitorWrapper {
  public static final String MUZZLE_FIELD_NAME = "muzzleReferenceMatcher";
  public static final String MUZZLE_METHOD_NAME = "getMuzzleReferenceMatcher";

  @Override
  public int mergeWriter(int flags) {
    return flags | ClassWriter.COMPUTE_MAXS;
  }

  @Override
  public int mergeReader(int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags) {
    return new GenerateMuzzleReferenceMatcherMethodAndField(
        classVisitor,
        implementationContext.getClassFileVersion().isAtLeast(ClassFileVersion.JAVA_V6));
  }

  private static class GenerateMuzzleReferenceMatcherMethodAndField extends ClassVisitor {

    private final boolean frames;

    private String instrumentationClassName;
    private Object instrumenter;

    public GenerateMuzzleReferenceMatcherMethodAndField(ClassVisitor classVisitor, boolean frames) {
      super(Opcodes.ASM7, classVisitor);
      this.frames = frames;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      this.instrumentationClassName = name;
      try {
        instrumenter =
            MuzzleCodeGenerator.class
                .getClassLoader()
                .loadClass(Utils.getClassName(instrumentationClassName))
                .getDeclaredConstructor()
                .newInstance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if (MUZZLE_FIELD_NAME.equals(name)) {
        // muzzle field has been generated
        // by previous compilation
        // ignore and recompute in visitEnd
        return null;
      }
      return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (MUZZLE_METHOD_NAME.equals(name)) {
        // muzzle getter has been generated
        // by previous compilation
        // ignore and recompute in visitEnd
        return null;
      }
      MethodVisitor methodVisitor =
          super.visitMethod(access, name, descriptor, signature, exceptions);
      if ("<init>".equals(name)) {
        methodVisitor = new InitializeReferenceMatcherField(methodVisitor);
      }
      return methodVisitor;
    }

    public Reference[] generateReferences() {
      Map<String, Reference> references = new HashMap<>();
      Set<String> adviceClassNames = Collections.emptySet();
      if (instrumenter instanceof InstrumentationModule) {
        adviceClassNames =
            ((InstrumentationModule) instrumenter)
                .typeInstrumentations().stream()
                    .flatMap(
                        typeInstrumentation -> typeInstrumentation.transformers().values().stream())
                    .collect(Collectors.toSet());
      } else if (instrumenter instanceof Instrumenter.Default) {
        adviceClassNames =
            new HashSet<>(((Instrumenter.Default) instrumenter).transformers().values());
      }

      for (String adviceClass : adviceClassNames) {
        for (Map.Entry<String, Reference> entry :
            ReferenceCollector.collectReferencesFrom(adviceClass).entrySet()) {
          if (references.containsKey(entry.getKey())) {
            references.put(entry.getKey(), references.get(entry.getKey()).merge(entry.getValue()));
          } else {
            references.put(entry.getKey(), entry.getValue());
          }
        }
      }
      return references.values().toArray(new Reference[0]);
    }

    @Override
    public void visitEnd() {
      { // generate getMuzzleReferenceMatcher method
        /*
         * protected synchronized ReferenceMatcher getMuzzleReferenceMatcher() {
         *   if (null == this.muzzleReferenceMatcher) {
         *     this.muzzleReferenceMatcher = new ReferenceMatcher(this.helperClassNames(),
         *                                                        new Reference[]{
         *                                                                       //reference builders
         *                                                                       });
         *   }
         *   return this.muzzleReferenceMatcher;
         * }
         */
        try {
          MethodVisitor mv =
              super.visitMethod(
                  Opcodes.ACC_PROTECTED + Opcodes.ACC_SYNCHRONIZED,
                  MUZZLE_METHOD_NAME,
                  "()Lio/opentelemetry/javaagent/tooling/muzzle/matcher/ReferenceMatcher;",
                  null,
                  null);

          mv.visitCode();
          Label start = new Label();
          Label ret = new Label();
          Label finish = new Label();

          mv.visitLabel(start);
          mv.visitInsn(Opcodes.ACONST_NULL);
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(
              Opcodes.GETFIELD,
              instrumentationClassName,
              MUZZLE_FIELD_NAME,
              "Lio/opentelemetry/javaagent/tooling/muzzle/matcher/ReferenceMatcher;");
          mv.visitJumpInsn(Opcodes.IF_ACMPNE, ret);

          mv.visitVarInsn(Opcodes.ALOAD, 0);

          mv.visitTypeInsn(
              Opcodes.NEW, "io/opentelemetry/javaagent/tooling/muzzle/matcher/ReferenceMatcher");
          mv.visitInsn(Opcodes.DUP);

          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              instrumentationClassName,
              "helperClassNames",
              "()[Ljava/lang/String;",
              false);

          Reference[] references = generateReferences();
          mv.visitLdcInsn(references.length);
          mv.visitTypeInsn(
              Opcodes.ANEWARRAY, "io/opentelemetry/javaagent/tooling/muzzle/Reference");

          for (int i = 0; i < references.length; ++i) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(i);
            mv.visitTypeInsn(
                Opcodes.NEW, "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(references[i].getClassName());
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
            for (Reference.Source source : references[i].getSources()) {
              mv.visitLdcInsn(source.getName());
              mv.visitLdcInsn(source.getLine());
              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                  "withSource",
                  "(Ljava/lang/String;I)Lio/opentelemetry/javaagent/tooling/muzzle/Reference$Builder;",
                  false);
            }
            for (Reference.Flag flag : references[i].getFlags()) {
              String enumClassName = getEnumClassInternalName(flag);
              mv.visitFieldInsn(
                  Opcodes.GETSTATIC, enumClassName, flag.name(), "L" + enumClassName + ";");
              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                  "withFlag",
                  "(Lio/opentelemetry/javaagent/tooling/muzzle/Reference$Flag;)Lio/opentelemetry/javaagent/tooling/muzzle/Reference$Builder;",
                  false);
            }
            if (null != references[i].getSuperName()) {
              mv.visitLdcInsn(references[i].getSuperName());
              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                  "withSuperName",
                  "(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/Reference$Builder;",
                  false);
            }
            for (String interfaceName : references[i].getInterfaces()) {
              mv.visitLdcInsn(interfaceName);
              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                  "withInterface",
                  "(Ljava/lang/String;)Lio/opentelemetry/javaagent/tooling/muzzle/Reference$Builder;",
                  false);
            }
            for (Reference.Field field : references[i].getFields()) {
              { // sources
                mv.visitLdcInsn(field.getSources().size());
                mv.visitTypeInsn(
                    Opcodes.ANEWARRAY,
                    "io/opentelemetry/javaagent/tooling/muzzle/Reference$Source");

                int j = 0;
                for (Reference.Source source : field.getSources()) {
                  mv.visitInsn(Opcodes.DUP);
                  mv.visitLdcInsn(j);

                  mv.visitTypeInsn(
                      Opcodes.NEW, "io/opentelemetry/javaagent/tooling/muzzle/Reference$Source");
                  mv.visitInsn(Opcodes.DUP);
                  mv.visitLdcInsn(source.getName());
                  mv.visitLdcInsn(source.getLine());
                  mv.visitMethodInsn(
                      Opcodes.INVOKESPECIAL,
                      "io/opentelemetry/javaagent/tooling/muzzle/Reference$Source",
                      "<init>",
                      "(Ljava/lang/String;I)V",
                      false);

                  mv.visitInsn(Opcodes.AASTORE);
                  ++j;
                }
              }

              { // flags
                mv.visitLdcInsn(field.getFlags().size());
                mv.visitTypeInsn(
                    Opcodes.ANEWARRAY, "io/opentelemetry/javaagent/tooling/muzzle/Reference$Flag");

                int j = 0;
                for (Reference.Flag flag : field.getFlags()) {
                  mv.visitInsn(Opcodes.DUP);
                  mv.visitLdcInsn(j);
                  String enumClassName = getEnumClassInternalName(flag);
                  mv.visitFieldInsn(
                      Opcodes.GETSTATIC, enumClassName, flag.name(), "L" + enumClassName + ";");
                  mv.visitInsn(Opcodes.AASTORE);
                  ++j;
                }
              }

              mv.visitLdcInsn(field.getName());

              { // field type
                mv.visitLdcInsn(field.getType().getDescriptor());
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(Type.class),
                    "getType",
                    Type.getMethodDescriptor(Type.class.getMethod("getType", String.class)),
                    false);
              }

              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                  "withField",
                  Type.getMethodDescriptor(
                      Reference.Builder.class.getMethod(
                          "withField",
                          Reference.Source[].class,
                          Reference.Flag[].class,
                          String.class,
                          Type.class)),
                  false);
            }
            for (Reference.Method method : references[i].getMethods()) {
              mv.visitLdcInsn(method.getSources().size());
              mv.visitTypeInsn(
                  Opcodes.ANEWARRAY, "io/opentelemetry/javaagent/tooling/muzzle/Reference$Source");
              int j = 0;
              for (Reference.Source source : method.getSources()) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(j);

                mv.visitTypeInsn(
                    Opcodes.NEW, "io/opentelemetry/javaagent/tooling/muzzle/Reference$Source");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(source.getName());
                mv.visitLdcInsn(source.getLine());
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "io/opentelemetry/javaagent/tooling/muzzle/Reference$Source",
                    "<init>",
                    "(Ljava/lang/String;I)V",
                    false);

                mv.visitInsn(Opcodes.AASTORE);
                ++j;
              }

              mv.visitLdcInsn(method.getFlags().size());
              mv.visitTypeInsn(
                  Opcodes.ANEWARRAY, "io/opentelemetry/javaagent/tooling/muzzle/Reference$Flag");
              j = 0;
              for (Reference.Flag flag : method.getFlags()) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(j);
                String enumClassName = getEnumClassInternalName(flag);
                mv.visitFieldInsn(
                    Opcodes.GETSTATIC, enumClassName, flag.name(), "L" + enumClassName + ";");
                mv.visitInsn(Opcodes.AASTORE);
                ++j;
              }

              mv.visitLdcInsn(method.getName());

              { // return type
                mv.visitLdcInsn(method.getReturnType().getDescriptor());
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(Type.class),
                    "getType",
                    Type.getMethodDescriptor(Type.class.getMethod("getType", String.class)),
                    false);
              }

              mv.visitLdcInsn(method.getParameterTypes().size());
              mv.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(Type.class));
              j = 0;
              for (Type parameterType : method.getParameterTypes()) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(j);

                mv.visitLdcInsn(parameterType.getDescriptor());
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(Type.class),
                    "getType",
                    Type.getMethodDescriptor(Type.class.getMethod("getType", String.class)),
                    false);

                mv.visitInsn(Opcodes.AASTORE);
                j++;
              }

              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                  "withMethod",
                  Type.getMethodDescriptor(
                      Reference.Builder.class.getMethod(
                          "withMethod",
                          Reference.Source[].class,
                          Reference.Flag[].class,
                          String.class,
                          Type.class,
                          Type[].class)),
                  false);
            }
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "io/opentelemetry/javaagent/tooling/muzzle/Reference$Builder",
                "build",
                "()Lio/opentelemetry/javaagent/tooling/muzzle/Reference;",
                false);
            mv.visitInsn(Opcodes.AASTORE);
          }

          mv.visitMethodInsn(
              Opcodes.INVOKESPECIAL,
              "io/opentelemetry/javaagent/tooling/muzzle/matcher/ReferenceMatcher",
              "<init>",
              "([Ljava/lang/String;[Lio/opentelemetry/javaagent/tooling/muzzle/Reference;)V",
              false);
          mv.visitFieldInsn(
              Opcodes.PUTFIELD,
              instrumentationClassName,
              MUZZLE_FIELD_NAME,
              "Lio/opentelemetry/javaagent/tooling/muzzle/matcher/ReferenceMatcher;");

          mv.visitLabel(ret);
          if (frames) {
            mv.visitFrame(Opcodes.F_SAME, 1, null, 0, null);
          }
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(
              Opcodes.GETFIELD,
              instrumentationClassName,
              MUZZLE_FIELD_NAME,
              "Lio/opentelemetry/javaagent/tooling/muzzle/matcher/ReferenceMatcher;");
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitLabel(finish);

          mv.visitLocalVariable(
              "this", "L" + instrumentationClassName + ";", null, start, finish, 0);
          mv.visitMaxs(0, 0); // recomputed
          mv.visitEnd();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      super.visitField(
          Opcodes.ACC_PRIVATE + Opcodes.ACC_VOLATILE,
          MUZZLE_FIELD_NAME,
          Type.getDescriptor(ReferenceMatcher.class),
          null,
          null);
      super.visitEnd();
    }

    private static final Pattern ANONYMOUS_ENUM_CONSTANT_CLASS =
        Pattern.compile("(?<enumClass>.*)\\$[0-9]+$");

    // drops "$1" suffix for enum constants that override/implement super class methods
    private String getEnumClassInternalName(Reference.Flag flag) {
      String fullInternalName = Utils.getInternalName(flag.getClass().getName());
      Matcher m = ANONYMOUS_ENUM_CONSTANT_CLASS.matcher(fullInternalName);
      return m.matches() ? m.group("enumClass") : fullInternalName;
    }

    /**
     * Appends the {@code ReferenceMatcher} field initialization at the end of a method/constructor.
     */
    private class InitializeReferenceMatcherField extends MethodVisitor {
      public InitializeReferenceMatcherField(MethodVisitor methodVisitor) {
        super(Opcodes.ASM7, methodVisitor);
      }

      @Override
      public void visitInsn(int opcode) {
        if (opcode == Opcodes.RETURN) {
          super.visitVarInsn(Opcodes.ALOAD, 0);
          super.visitInsn(Opcodes.ACONST_NULL);
          super.visitFieldInsn(
              Opcodes.PUTFIELD,
              instrumentationClassName,
              MUZZLE_FIELD_NAME,
              Type.getDescriptor(ReferenceMatcher.class));
        }
        super.visitInsn(opcode);
      }
    }
  }
}
