package com.digitalbank.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal HTTP helper: the tests speak to the services over real HTTP. */
final class Rest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Response(int status, JsonNode body, String raw) {

        JsonNode require(int expected) {
            if (status != expected) {
                throw new AssertionError("expected HTTP " + expected + " but got " + status + ": " + raw);
            }
            return body;
        }
    }

    static Response post(String url, String token, String json, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));

        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return send(request.build());
    }

    static Response get(String url, String token) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
    }

    private static Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode body = raw == null || raw.isBlank() ? MAPPER.nullNode() : parse(raw);
            return new Response(response.statusCode(), body, raw);
        } catch (Exception e) {
            throw new IllegalStateException("request to " + request.uri() + " failed", e);
        }
    }

    private static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return MAPPER.getNodeFactory().textNode(raw);
        }
    }

    private Rest() {
    }
}
