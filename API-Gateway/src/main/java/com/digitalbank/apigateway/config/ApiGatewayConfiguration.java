package com.digitalbank.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiGatewayConfiguration {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder,
                                           @Value("${gateway.routes.auth-url:http://localhost:8081}") String authServiceUrl,
                                           @Value("${gateway.routes.customer-url:http://localhost:8082}") String customerServiceUrl,
                                           @Value("${gateway.routes.account-url:http://localhost:8083}") String accountServiceUrl,
                                           @Value("${gateway.routes.biller-url:http://localhost:8084}") String billerServiceUrl,
                                           @Value("${gateway.routes.payment-url:http://localhost:8085}") String paymentServiceUrl,
                                           @Value("${gateway.routes.worker-url:http://localhost:8086}") String workerServiceUrl,
                                           @Value("${gateway.routes.settlement-url:http://localhost:8087}") String settlementServiceUrl) {
        return builder.routes()
                .route("auth-service", route -> route.path("/auth/**")
                        .filters(filter -> filter.rewritePath("/auth/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri(authServiceUrl))
                .route("customer-service", route -> route.path("/customers/**")
                        .filters(filter -> filter.rewritePath("/customers/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri(customerServiceUrl))
                .route("account-service", route -> route.path("/accounts/**")
                        .filters(filter -> filter.rewritePath("/accounts/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri(accountServiceUrl))
                .route("biller-service", route -> route.path("/billers/**")
                        .filters(filter -> filter.rewritePath("/billers/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri(billerServiceUrl))
                .route("payment-service", route -> route.path("/payments/**")
                        .filters(filter -> filter.rewritePath("/payments/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri(paymentServiceUrl))
                .route("worker-service", route -> route.path("/workers/**")
                        .filters(filter -> filter.rewritePath("/workers/(?<segment>.*)", "/api/mock/central1/${segment}"))
                        .uri(workerServiceUrl))
                .route("settlement-service", route -> route.path("/settlements/**")
                        .filters(filter -> filter.rewritePath("/settlements/(?<segment>.*)", "/api/v1/${segment}"))
                        .uri(settlementServiceUrl))
                .route("auth-openapi", route -> route.path("/v3/api-docs/auth")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/auth", "/v3/api-docs"))
                        .uri(authServiceUrl))
                .route("customer-openapi", route -> route.path("/v3/api-docs/customers")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/customers", "/v3/api-docs"))
                        .uri(customerServiceUrl))
                .route("account-openapi", route -> route.path("/v3/api-docs/accounts")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/accounts", "/v3/api-docs"))
                        .uri(accountServiceUrl))
                .route("biller-openapi", route -> route.path("/v3/api-docs/billers")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/billers", "/v3/api-docs"))
                        .uri(billerServiceUrl))
                .route("payment-openapi", route -> route.path("/v3/api-docs/payments")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/payments", "/v3/api-docs"))
                        .uri(paymentServiceUrl))
                .route("worker-openapi", route -> route.path("/v3/api-docs/workers")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/workers", "/v3/api-docs"))
                        .uri(workerServiceUrl))
                .route("settlement-openapi", route -> route.path("/v3/api-docs/settlements")
                        .filters(filter -> filter.rewritePath("/v3/api-docs/settlements", "/v3/api-docs"))
                        .uri(settlementServiceUrl))
                .build();
    }
}
