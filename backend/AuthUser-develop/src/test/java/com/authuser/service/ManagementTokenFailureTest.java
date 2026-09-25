package com.authuser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.commons.exception.UpstreamException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Failing to obtain a management token is this platform's problem, and says so.
 *
 * <p>Every way this call can fail - a rejected client secret, a wrong audience,
 * an unreachable tenant - is a fault in configuration held by this service. None
 * of them is anything a caller did. Unhandled, they surfaced as a 500 from
 * AuthUser, which reads as a bad request and points an operator at the wrong
 * thing entirely.</p>
 */
class ManagementTokenFailureTest {

    private RestTemplate restTemplate;
    private ManagementTokenService service;

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        service = new ManagementTokenService();

        restTemplate = mock(RestTemplate.class);
        Field rt = ManagementTokenService.class.getDeclaredField("rt");
        rt.setAccessible(true);
        rt.set(service, restTemplate);

        // @Value fields are null outside a context; the client id is set so the
        // leakage assertion below has something real to look for.
        set("clientId", "mgmt-client-id-abc123");
        set("clientSecret", "mgmt-client-secret-xyz789");

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    private void set(String field, String value) throws Exception {
        Field f = ManagementTokenService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("a rejected client secret is an upstream failure, not a 500 of ours")
    void tokenFetchFailureIsUpstream() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                        "{\"error\":\"access_denied\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.getBearer())
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("a token response with nothing in it is upstream too")
    @SuppressWarnings("unchecked")
    void missingAccessTokenIsUpstream() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("token_type", "Bearer"), HttpStatus.OK));

        // A 200 carrying no access_token is the same outcome as a refusal: this
        // service cannot call Auth0. It used to raise a bare RuntimeException,
        // which the catch-all turned into the same uninformative 500.
        assertThatThrownBy(() -> service.getBearer())
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("the credentials are never written to the log")
    void credentialsAreNeverLogged() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                        "{\"error\":\"access_denied\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        Throwable thrown = catchThrowable(() -> service.getBearer());

        String logged = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        // Auth0's token error body echoes the client_id back. Logging the body
        // wholesale would put it, and anything Auth0 chooses to add later, into
        // the platform log.
        assertThat(logged).doesNotContain("mgmt-client-id-abc123");
        assertThat(logged).doesNotContain("mgmt-client-secret-xyz789");
        assertThat(thrown.getMessage()).doesNotContain("mgmt-client-secret-xyz789");

        // The status is kept, because that is what tells an operator whether the
        // credentials were refused or the tenant was simply down.
        assertThat(logged).contains("401");
    }
}
