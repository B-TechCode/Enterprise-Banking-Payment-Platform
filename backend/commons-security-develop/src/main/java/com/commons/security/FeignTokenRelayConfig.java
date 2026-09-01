package com.commons.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/**
 * FeignTokenRelayConfig ensures that when one microservice calls another
 * using a Feign client, the original user's JWT access token is automatically
 * forwarded in the outgoing HTTP request.
 *
 * <p>This is essential for implementing <b>token relay</b> — where downstream services
 * can continue to authorize requests based on the same user identity and permissions.</p>
 *
 * <p>Without this, the second service would never know "who" the user is, because the token
 * would not be propagated across service boundaries.</p>
 */
@Configuration
public class FeignTokenRelayConfig {

  // The expected audience (API identifier) that must be present in the token
  @Value("${auth0.audience}")
  private String expectedAudience;

  /**
   * A Feign RequestInterceptor bean that automatically attaches the user's Bearer token
   * to outgoing HTTP requests if the current Authentication is a JwtAuthenticationToken.
   *
   * <p>This allows downstream microservices to receive and validate the same access token
   * that the user originally presented — enabling secure, end-to-end identity propagation.</p>
   */
  @Bean
  public RequestInterceptor relayUserJwt() {
    return new RequestInterceptor() {
      @Override
      public void apply(RequestTemplate tpl) {
        // Get the current authentication object from the security context
        Authentication a = SecurityContextHolder.getContext().getAuthentication();

        // Proceed only if the current user is authenticated with a JWT
        if (a instanceof JwtAuthenticationToken) {
          Jwt jwt = ((JwtAuthenticationToken) a).getToken();

          // Extract the 'aud' (audience) claim from the JWT.
          //
          // Auth0 emits 'aud' as a bare JSON string when a token has a single
          // audience, and as an array when it has several. Spring Security
          // normally normalises the claim to a Collection, so a List-only check
          // works today - but it depends on that normalisation rather than on
          // the claim itself, and a custom JwtDecoder would silently break the
          // relay with no error at this point. DefaultSecurityConfig already
          // accepts either shape; this mirrors it so both classes agree.
          Object aud = jwt.getClaims().get("aud");

          // Relay the token only if the expected audience is present, so we
          // never forward a token that was issued for a different API.
          if (audienceMatches(aud)) {
            // Add the Authorization header with the Bearer token to the outgoing Feign request
            tpl.header("Authorization", "Bearer " + jwt.getTokenValue());
          }
        }
      }

      /**
       * Whether the token's audience claim contains the audience this service
       * expects. Accepts the claim as a single String or as a Collection of
       * values, matching how Auth0 represents one versus several audiences.
       */
      private boolean audienceMatches(Object aud) {
        if (aud instanceof String) {
          return expectedAudience.equals(aud);
        }
        if (aud instanceof Collection<?> values) {
          for (Object value : values) {
            if (expectedAudience.equals(String.valueOf(value))) {
              return true;
            }
          }
        }
        return false;
      }
    };
  }
}
