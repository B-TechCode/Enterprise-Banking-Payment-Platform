package com.digitalbank.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Auth0 for the duration of a test run.
 *
 * <p>The services validate tokens exactly as they do in production: the decoder
 * discovers this server from its issuer URL, downloads the signing key, and
 * checks the issuer and audience of every token. Only the identity provider is
 * swapped, which is what lets a test decide precisely which customer a request
 * comes from and which scopes it carries.</p>
 *
 * <p>It runs inside the test JVM and is reachable from the containers through
 * Testcontainers' host gateway. It serves the token endpoint at
 * {@code /oauth/token} because the Payment Orchestrator derives that path from
 * the issuer when it fetches its own machine-to-machine token; serving the path
 * it already expects avoids overriding any service configuration.</p>
 */
final class IdentityProviderStub implements AutoCloseable {

    /** Namespaced claim the platform reads a customer identity from. */
    static final String CUSTOMER_ID_CLAIM = "https://mockbank/customer_id";

    static final String AUDIENCE = "https://mockbank/api";

    /** Scopes a service token carries; it must be able to release and debit. */
    private static final String M2M_SCOPES = "fdx:accounts.write fdx:accounts.read";

    private final HttpServer server;
    private final RSAKey signingKey;
    private final String issuer;

    private IdentityProviderStub(HttpServer server, RSAKey signingKey, String issuer) {
        this.server = server;
        this.signingKey = signingKey;
        this.issuer = issuer;
    }

    /**
     * Starts the server and returns it.
     *
     * @param hostFromContainers the address containers use to reach the test
     *                           JVM; the issuer must be identical everywhere,
     *                           because the services compare it to the token's
     *                           iss claim
     */
    static IdentityProviderStub start(String hostFromContainers) throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("integration-test-key").generate();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String issuer = "http://" + hostFromContainers + ":" + server.getAddress().getPort() + "/";

        IdentityProviderStub provider = new IdentityProviderStub(server, key, issuer);

        server.createContext("/.well-known/openid-configuration", exchange -> provider.respond(exchange, """
                {
                  "issuer": "%s",
                  "jwks_uri": "%sjwks.json",
                  "token_endpoint": "%soauth/token",
                  "response_types_supported": ["token"],
                  "subject_types_supported": ["public"],
                  "id_token_signing_alg_values_supported": ["RS256"]
                }
                """.formatted(issuer, issuer, issuer)));

        server.createContext("/jwks.json", exchange ->
                provider.respond(exchange, new JWKSet(key.toPublicJWK()).toString()));

        // The orchestrator's client-credentials grant. The token it receives
        // carries no customer identity, which is what Account Service treats as
        // a trusted service call.
        server.createContext("/oauth/token", exchange -> provider.respond(exchange, """
                {
                  "access_token": "%s",
                  "token_type": "Bearer",
                  "expires_in": 3600
                }
                """.formatted(provider.serviceToken())));

        server.setExecutor(null);
        server.start();
        return provider;
    }

    String issuer() {
        return issuer;
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** A token for an end user: carries their customer id and the given scopes. */
    String userToken(String customerId, String... scopes) {
        return sign(new JWTClaimsSet.Builder()
                .subject("auth0|" + customerId)
                .claim(CUSTOMER_ID_CLAIM, customerId)
                .claim("scope", String.join(" ", scopes)));
    }

    /**
     * A token for a service: the client-credentials grant type and no customer
     * identity, the combination Account Service admits past its ownership check.
     */
    private String serviceToken() {
        return sign(new JWTClaimsSet.Builder()
                .subject("integration-test-client@clients")
                .claim("gty", "client-credentials")
                .claim("scope", M2M_SCOPES));
    }

    private String sign(JWTClaimsSet.Builder claims) {
        try {
            JWTClaimsSet claimsSet = claims
                    .issuer(issuer)
                    .audience(AUDIENCE)
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claimsSet);

            jwt.sign(new RSASSASigner((RSAPrivateKey) signingKey.toRSAKey().toPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign a test token", e);
        }
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static List<String> allUserScopes() {
        return List.of("fdx:accounts.read", "fdx:accounts.write",
                "fdx:bill.read", "fdx:bill.write", "fdx:transactions.read");
    }
}
