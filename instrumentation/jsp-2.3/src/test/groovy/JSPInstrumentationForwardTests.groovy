/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.api.trace.Span.Kind.SERVER

import com.google.common.io.Files
import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.instrumentation.test.utils.OkHttpUtils
import io.opentelemetry.instrumentation.test.utils.PortUtils
import io.opentelemetry.api.trace.attributes.SemanticAttributes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.catalina.Context
import org.apache.catalina.startup.Tomcat
import org.apache.jasper.JasperException
import spock.lang.Shared
import spock.lang.Unroll

class JSPInstrumentationForwardTests extends AgentTestRunner {

  static {
    // skip jar scanning using environment variables:
    // http://tomcat.apache.org/tomcat-7.0-doc/config/systemprops.html#JAR_Scanning
    // having this set allows us to test with old versions of the tomcat api since
    // JarScanFilter did not exist in the tomcat 7 api
    System.setProperty("org.apache.catalina.startup.ContextConfig.jarsToSkip", "*")
    System.setProperty("org.apache.catalina.startup.TldConfig.jarsToSkip", "*")
  }

  @Shared
  int port
  @Shared
  Tomcat tomcatServer
  @Shared
  Context appContext
  @Shared
  String jspWebappContext = "jsptest-context"

  @Shared
  File baseDir
  @Shared
  String baseUrl

  OkHttpClient client = OkHttpUtils.client()

  def setupSpec() {
    baseDir = Files.createTempDir()
    baseDir.deleteOnExit()

    port = PortUtils.randomOpenPort()

    tomcatServer = new Tomcat()
    tomcatServer.setBaseDir(baseDir.getAbsolutePath())
    tomcatServer.setPort(port)
    tomcatServer.getConnector()
    // comment to debug
    tomcatServer.setSilent(true)
    // this is needed in tomcat 9, this triggers the creation of a connector, will not
    // affect tomcat 7 and 8
    // https://stackoverflow.com/questions/48998387/code-works-with-embedded-apache-tomcat-8-but-not-with-9-whats-changed
    tomcatServer.getConnector()

    baseUrl = "http://localhost:$port/$jspWebappContext"

    appContext = tomcatServer.addWebapp("/$jspWebappContext",
      JSPInstrumentationForwardTests.getResource("/webapps/jsptest").getPath())

    tomcatServer.start()
    System.out.println(
      "Tomcat server: http://" + tomcatServer.getHost().getName() + ":" + port + "/")
  }

  def cleanupSpec() {
    tomcatServer.stop()
    tomcatServer.destroy()
  }

  @Unroll
  def "non-erroneous GET forward to #forwardTo"() {
    setup:
    String reqUrl = baseUrl + "/$forwardFromFileName"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(0, 5) {
        span(0) {
          hasNoParent()
          name "/$jspWebappContext/$forwardFromFileName"
          kind SERVER
          errored false
          attributes {
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "http://localhost:$port/$jspWebappContext/$forwardFromFileName"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 200
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "HTTP/1.1"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
        span(1) {
          childOf span(0)
          name "Compile /$forwardFromFileName"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.$jspForwardFromClassPrefix$jspForwardFromClassName"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(2) {
          childOf span(0)
          name "Render /$forwardFromFileName"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
          }
        }
        span(3) {
          childOf span(2)
          name "Compile /$forwardDestFileName"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.$jspForwardDestClassPrefix$jspForwardDestClassName"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(4) {
          childOf span(2)
          name "Render /$forwardDestFileName"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/$forwardFromFileName"
            "jsp.requestURL" baseUrl + "/$forwardDestFileName"
          }
        }
      }
    }
    res.code() == 200

    cleanup:
    res.close()

    where:
    forwardTo         | forwardFromFileName                | forwardDestFileName | jspForwardFromClassName   | jspForwardFromClassPrefix | jspForwardDestClassName | jspForwardDestClassPrefix
    "no java jsp"     | "forwards/forwardToNoJavaJsp.jsp"  | "nojava.jsp"        | "forwardToNoJavaJsp_jsp"  | "forwards."               | "nojava_jsp"            | ""
    "normal java jsp" | "forwards/forwardToSimpleJava.jsp" | "common/loop.jsp"   | "forwardToSimpleJava_jsp" | "forwards."               | "loop_jsp"              | "common."
  }

  def "non-erroneous GET forward to plain HTML"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToHtml.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          hasNoParent()
          name "/$jspWebappContext/forwards/forwardToHtml.jsp"
          kind SERVER
          errored false
          attributes {
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "http://localhost:$port/$jspWebappContext/forwards/forwardToHtml.jsp"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 200
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "HTTP/1.1"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
        span(1) {
          childOf span(0)
          name "Compile /forwards/forwardToHtml.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToHtml_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(2) {
          childOf span(0)
          name "Render /forwards/forwardToHtml.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
          }
        }
      }
    }
    res.code() == 200

    cleanup:
    res.close()
  }

  def "non-erroneous GET forwarded to jsp with multiple includes"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToIncludeMulti.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(0, 9) {
        span(0) {
          hasNoParent()
          name "/$jspWebappContext/forwards/forwardToIncludeMulti.jsp"
          kind SERVER
          errored false
          attributes {
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "http://localhost:$port/$jspWebappContext/forwards/forwardToIncludeMulti.jsp"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 200
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "HTTP/1.1"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
        span(1) {
          childOf span(0)
          name "Compile /forwards/forwardToIncludeMulti.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToIncludeMulti_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(2) {
          childOf span(0)
          name "Render /forwards/forwardToIncludeMulti.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
          }
        }
        span(3) {
          childOf span(2)
          name "Compile /includes/includeMulti.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.includes.includeMulti_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(4) {
          childOf span(2)
          name "Render /includes/includeMulti.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToIncludeMulti.jsp"
            "jsp.requestURL" baseUrl + "/includes/includeMulti.jsp"
          }
        }
        span(5) {
          childOf span(4)
          name "Compile /common/javaLoopH2.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.common.javaLoopH2_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(6) {
          childOf span(4)
          name "Render /common/javaLoopH2.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToIncludeMulti.jsp"
            "jsp.requestURL" baseUrl + "/includes/includeMulti.jsp"
          }
        }
        span(7) {
          childOf span(4)
          name "Compile /common/javaLoopH2.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.common.javaLoopH2_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(8) {
          childOf span(4)
          name "Render /common/javaLoopH2.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToIncludeMulti.jsp"
            "jsp.requestURL" baseUrl + "/includes/includeMulti.jsp"
          }
        }
      }
    }
    res.code() == 200

    cleanup:
    res.close()
  }

  def "non-erroneous GET forward to another forward (2 forwards)"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToJspForward.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(0, 7) {
        span(0) {
          hasNoParent()
          name "/$jspWebappContext/forwards/forwardToJspForward.jsp"
          kind SERVER
          errored false
          attributes {
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "http://localhost:$port/$jspWebappContext/forwards/forwardToJspForward.jsp"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 200
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "HTTP/1.1"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
        span(1) {
          childOf span(0)
          name "Compile /forwards/forwardToJspForward.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToJspForward_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(2) {
          childOf span(0)
          name "Render /forwards/forwardToJspForward.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
          }
        }
        span(3) {
          childOf span(2)
          name "Compile /forwards/forwardToSimpleJava.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToSimpleJava_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(4) {
          childOf span(2)
          name "Render /forwards/forwardToSimpleJava.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToJspForward.jsp"
            "jsp.requestURL" baseUrl + "/forwards/forwardToSimpleJava.jsp"
          }
        }
        span(5) {
          childOf span(4)
          name "Compile /common/loop.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.common.loop_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(6) {
          childOf span(4)
          name "Render /common/loop.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToJspForward.jsp"
            "jsp.requestURL" baseUrl + "/common/loop.jsp"
          }
        }
      }
    }
    res.code() == 200

    cleanup:
    res.close()
  }

  def "forward to jsp with compile error should not produce a 2nd render span"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToCompileError.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          hasNoParent()
          name "/$jspWebappContext/forwards/forwardToCompileError.jsp"
          kind SERVER
          errored true
          errorEvent(JasperException, String)
          attributes {
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "http://localhost:$port/$jspWebappContext/forwards/forwardToCompileError.jsp"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 500
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "HTTP/1.1"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
        span(1) {
          childOf span(0)
          name "Compile /forwards/forwardToCompileError.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToCompileError_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(2) {
          childOf span(0)
          name "Render /forwards/forwardToCompileError.jsp"
          errored true
          errorEvent(JasperException, String)
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
          }
        }
        span(3) {
          childOf span(2)
          name "Compile /compileError.jsp"
          errored true
          errorEvent(JasperException, String)
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.compileError_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
      }
    }
    res.code() == 500

    cleanup:
    res.close()
  }

  def "forward to non existent jsp should be 404"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToNonExistent.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          hasNoParent()
          name "/$jspWebappContext/forwards/forwardToNonExistent.jsp"
          kind SERVER
          errored true
          attributes {
            "${SemanticAttributes.NET_PEER_IP.key()}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key()}" Long
            "${SemanticAttributes.HTTP_URL.key()}" "http://localhost:$port/$jspWebappContext/forwards/forwardToNonExistent.jsp"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 404
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "HTTP/1.1"
            "${SemanticAttributes.HTTP_USER_AGENT.key()}" String
            "${SemanticAttributes.HTTP_CLIENT_IP.key()}" "127.0.0.1"
          }
        }
        span(1) {
          childOf span(0)
          name "Compile /forwards/forwardToNonExistent.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToNonExistent_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
          }
        }
        span(2) {
          childOf span(0)
          name "Render /forwards/forwardToNonExistent.jsp"
          errored false
          attributes {
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
          }
        }
      }
    }
    res.code() == 404

    cleanup:
    res.close()
  }
}
