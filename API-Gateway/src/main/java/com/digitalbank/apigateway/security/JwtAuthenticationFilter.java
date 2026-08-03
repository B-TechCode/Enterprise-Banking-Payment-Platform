package com.digitalbank.apigateway.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtAuthenticationProperties properties;

    public JwtAuthenticationFilter(JwtAuthenticationProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or malformed Bearer token");
        }

        try {
            String token = authorization.substring(7);
            DecodedJWT decoded = JWT.require(Algorithm.HMAC256(properties.getSecret()))
                    .build()
                    .verify(token);

            String subject = decoded.getSubject();
            String roles = Objects.toString(decoded.getClaim("roles").asString(), "USER");

            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", subject)
                    .header("X-User-Roles", roles)
                    .header("X-Authenticated", "true")
                    .build();

            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
            return chain.filter(mutatedExchange);
        } catch (Exception ex) {
            LOGGER.warn("JWT authentication failed for path {}: {}", path, ex.getMessage());
            return unauthorized(exchange, "Invalid or expired JWT");
        }
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.contains("/public")
                || path.contains("/health");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        response.getHeaders().add("WWW-Authenticate", "Bearer");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}").getBytes())));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
