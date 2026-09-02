package com.digitalbank.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiGatewayConfiguration {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth-service", route -> route.path("/auth/**")
                        .filters(filter -> filter.rewritePath("/auth/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri("lb://auth-user"))
                .route("customer-service", route -> route.path("/customers/**")
                        .filters(filter -> filter.rewritePath("/customers/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri("lb://customer-service"))
                .route("account-service", route -> route.path("/accounts/**")
                        .filters(filter -> filter.rewritePath("/accounts/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri("lb://account-service"))
                .route("biller-service", route -> route.path("/billers/**")
                        .filters(filter -> filter.rewritePath("/billers/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri("lb://biller-service"))
                .route("payment-service", route -> route.path("/payments/**")
                        .filters(filter -> filter.rewritePath("/payments/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri("lb://payment-orchestrator"))
                .route("worker-service", route -> route.path("/workers/**")
                        .filters(filter -> filter.rewritePath("/workers/(?<segment>.*)", "/api/mock/central1/${segment}"))
                        .uri("lb://billpay-worker-service"))
                .route("ai-commerce-agent", route -> route.path("/ai/**")
                        .filters(filter -> filter.rewritePath("/ai/(?<segment>.*)", "/api/v1/agent/${segment}"))
                        .uri("lb://ai-commerce-agent"))
                .route("auth-openapi", route -> route.path("/v3/api-docs/auth")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/auth", "/v3/api-docs"))
                        .uri("lb://auth-user"))
                .route("customer-openapi", route -> route.path("/v3/api-docs/customers")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/customers", "/v3/api-docs"))
                        .uri("lb://customer-service"))
                .route("account-openapi", route -> route.path("/v3/api-docs/accounts")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/accounts", "/v3/api-docs"))
                        .uri("lb://account-service"))
                .route("biller-openapi", route -> route.path("/v3/api-docs/billers")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/billers", "/v3/api-docs"))
                        .uri("lb://biller-service"))
                .route("payment-openapi", route -> route.path("/v3/api-docs/payments")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/payments", "/v3/api-docs"))
                        .uri("lb://payment-orchestrator"))
                .route("worker-openapi", route -> route.path("/v3/api-docs/workers")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/workers", "/v3/api-docs"))
                        .uri("lb://billpay-worker-service"))
                .build();
    }
}
