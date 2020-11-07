/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.typedspan;

import io.opentelemetry.api.trace.Span;

public interface IdentitySemanticConvention {
  void end();

  Span getSpan();

  /**
   * Sets a value for enduser.id
   *
   * @param enduserId Username or client_id extracted from the access token or Authorization header
   *     in the inbound request from outside the system.
   */
  IdentitySemanticConvention setEnduserId(String enduserId);

  /**
   * Sets a value for enduser.role
   *
   * @param enduserRole Actual/assumed role the client is making the request under extracted from
   *     token or application security context.
   */
  IdentitySemanticConvention setEnduserRole(String enduserRole);

  /**
   * Sets a value for enduser.scope
   *
   * @param enduserScope Scopes or granted authorities the client currently possesses extracted from
   *     token or application security context. The value would come from the scope associated with
   *     an OAuth 2.0 Access Token or an attribute value in a SAML 2.0 Assertion.
   */
  IdentitySemanticConvention setEnduserScope(String enduserScope);
}
