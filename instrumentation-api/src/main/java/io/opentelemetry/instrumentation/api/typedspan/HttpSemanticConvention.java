/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.typedspan;

import io.opentelemetry.api.trace.Span;

public interface HttpSemanticConvention {
  void end();

  Span getSpan();

  /**
   * Sets a value for net.transport
   *
   * @param netTransport Transport protocol used. See note below.
   */
  HttpSemanticConvention setNetTransport(String netTransport);

  /**
   * Sets a value for net.peer.ip
   *
   * @param netPeerIp Remote address of the peer (dotted decimal for IPv4 or
   *     [RFC5952](https://tools.ietf.org/html/rfc5952) for IPv6).
   */
  HttpSemanticConvention setNetPeerIp(String netPeerIp);

  /**
   * Sets a value for net.peer.port
   *
   * @param netPeerPort Remote port number.
   */
  HttpSemanticConvention setNetPeerPort(long netPeerPort);

  /**
   * Sets a value for net.peer.name
   *
   * @param netPeerName Remote hostname or similar, see note below.
   */
  HttpSemanticConvention setNetPeerName(String netPeerName);

  /**
   * Sets a value for net.host.ip
   *
   * @param netHostIp Like `net.peer.ip` but for the host IP. Useful in case of a multi-IP host.
   */
  HttpSemanticConvention setNetHostIp(String netHostIp);

  /**
   * Sets a value for net.host.port
   *
   * @param netHostPort Like `net.peer.port` but for the host port.
   */
  HttpSemanticConvention setNetHostPort(long netHostPort);

  /**
   * Sets a value for net.host.name
   *
   * @param netHostName Local hostname or similar, see note below.
   */
  HttpSemanticConvention setNetHostName(String netHostName);

  /**
   * Sets a value for http.method
   *
   * @param httpMethod HTTP request method.
   */
  HttpSemanticConvention setHttpMethod(String httpMethod);

  /**
   * Sets a value for http.url
   *
   * @param httpUrl Full HTTP request URL in the form `scheme://host[:port]/path?query[#fragment]`.
   *     Usually the fragment is not transmitted over HTTP, but if it is known, it should be
   *     included nevertheless.
   */
  HttpSemanticConvention setHttpUrl(String httpUrl);

  /**
   * Sets a value for http.target
   *
   * @param httpTarget The full request target as passed in a HTTP request line or equivalent.
   */
  HttpSemanticConvention setHttpTarget(String httpTarget);

  /**
   * Sets a value for http.host
   *
   * @param httpHost The value of the [HTTP host
   *     header](https://tools.ietf.org/html/rfc7230#section-5.4). When the header is empty or not
   *     present, this attribute should be the same.
   */
  HttpSemanticConvention setHttpHost(String httpHost);

  /**
   * Sets a value for http.scheme
   *
   * @param httpScheme The URI scheme identifying the used protocol.
   */
  HttpSemanticConvention setHttpScheme(String httpScheme);

  /**
   * Sets a value for http.status_code
   *
   * @param httpStatusCode [HTTP response status
   *     code](https://tools.ietf.org/html/rfc7231#section-6).
   */
  HttpSemanticConvention setHttpStatusCode(long httpStatusCode);

  /**
   * Sets a value for http.status_text
   *
   * @param httpStatusText [HTTP reason phrase](https://tools.ietf.org/html/rfc7230#section-3.1.2).
   */
  HttpSemanticConvention setHttpStatusText(String httpStatusText);

  /**
   * Sets a value for http.flavor
   *
   * @param httpFlavor Kind of HTTP protocol used.
   *     <p>If `net.transport` is not specified, it can be assumed to be `IP.TCP` except if
   *     `http.flavor` is `QUIC`, in which case `IP.UDP` is assumed.
   */
  HttpSemanticConvention setHttpFlavor(String httpFlavor);

  /**
   * Sets a value for http.user_agent
   *
   * @param httpUserAgent Value of the [HTTP
   *     User-Agent](https://tools.ietf.org/html/rfc7231#section-5.5.3) header sent by the client.
   */
  HttpSemanticConvention setHttpUserAgent(String httpUserAgent);

  /**
   * Sets a value for http.request_content_length
   *
   * @param httpRequestContentLength The size of the request payload body in bytes. This is the
   *     number of bytes transferred excluding headers and is often, but not always, present as the
   *     [Content-Length](https://tools.ietf.org/html/rfc7230#section-3.3.2) header. For requests
   *     using transport encoding, this should be the compressed size.
   */
  HttpSemanticConvention setHttpRequestContentLength(long httpRequestContentLength);

  /**
   * Sets a value for http.request_content_length_uncompressed
   *
   * @param httpRequestContentLengthUncompressed The size of the uncompressed request payload body
   *     after transport decoding. Not set if transport encoding not used.
   */
  HttpSemanticConvention setHttpRequestContentLengthUncompressed(
      long httpRequestContentLengthUncompressed);

  /**
   * Sets a value for http.response_content_length
   *
   * @param httpResponseContentLength The size of the response payload body in bytes. This is the
   *     number of bytes transferred excluding headers and is often, but not always, present as the
   *     [Content-Length](https://tools.ietf.org/html/rfc7230#section-3.3.2) header. For requests
   *     using transport encoding, this should be the compressed size.
   */
  HttpSemanticConvention setHttpResponseContentLength(long httpResponseContentLength);

  /**
   * Sets a value for http.response_content_length_uncompressed
   *
   * @param httpResponseContentLengthUncompressed The size of the uncompressed response payload body
   *     after transport decoding. Not set if transport encoding not used.
   */
  HttpSemanticConvention setHttpResponseContentLengthUncompressed(
      long httpResponseContentLengthUncompressed);
}
