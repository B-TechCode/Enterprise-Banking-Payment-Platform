package com.authuser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Where the generated password is allowed to go: to Auth0, once, and nowhere
 * else.
 *
 * <p>A generated password is only an improvement on a shared literal if nothing
 * records it. These tests pin that it reaches the Auth0 request body, differs
 * per user, and appears in neither the response this service returns nor
 * anything it logs.</p>
 */
class Auth0UserServicePasswordTest {

    private ManagementTokenService tokens;
    private RestTemplate restTemplate;
    private Auth0UserService service;

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        tokens = mock(ManagementTokenService.class);
        when(tokens.getBearer()).thenReturn("Bearer management-token");

        service = new Auth0UserService(tokens, new InitialPasswordGenerator());

        // The RestTemplate is constructed in a field initialiser rather than
        // injected, so it is replaced here rather than mocked through the
        // constructor. Reaching in like this is deliberate: it lets the test
        // exist without reshaping production code that other items will touch.
        restTemplate = mock(RestTemplate.class);
        Field rt = Auth0UserService.class.getDeclaredField("rt");
        rt.setAccessible(true);
        rt.set(service, restTemplate);

        // createDbUser now refuses before creating anything when no role is
        // configured, so these fixtures have to name one. That guard is the
        // subject of Auth0RoleProvisioningTest; here it only has to be
        // satisfied.
        Field roleId = Auth0UserService.class.getDeclaredField("roleId");
        roleId.setAccessible(true);
        roleId.set(service, "rol_test");

        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("user_id", "auth0|abc123", "email", "ada@example.com"),
                        HttpStatus.CREATED));
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
    }

    @SuppressWarnings("unchecked")
    private String capturePasswordSentToAuth0() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .postForEntity(anyString(), captor.capture(), eq(Map.class));
        return (String) captor.getValue().getBody().get("password");
    }

    @Test
    @DisplayName("a password is sent to Auth0, and it is not the old shared literal")
    void passwordIsGeneratedNotHardcoded() {
        service.createDbUser("ada@example.com", "cust-1");

        String sent = capturePasswordSentToAuth0();
        assertThat(sent).isNotBlank();
        assertThat(sent).isNotEqualTo("default-password");
        assertThat(sent).hasSize(32);
    }

    @Test
    @DisplayName("two customers do not share a password")
    void eachCustomerGetsItsOwnPassword() {
        Set<String> sent = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            RestTemplate perCall = mock(RestTemplate.class);
            when(perCall.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(
                            Map.of("user_id", "auth0|abc" + i), HttpStatus.CREATED));
            when(perCall.postForEntity(anyString(), any(), eq(Void.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));
            swapRestTemplate(perCall);

            service.createDbUser("customer" + i + "@example.com", "cust-" + i);

            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(HttpEntity.class);
            org.mockito.Mockito.verify(perCall)
                    .postForEntity(anyString(), captor.capture(), eq(Map.class));
            sent.add((String) captor.getValue().getBody().get("password"));
        }

        assertThat(sent).hasSize(20);
    }

    @Test
    @DisplayName("the password is not returned to the caller")
    void passwordIsNeverReturned() {
        Map created = service.createDbUser("ada@example.com", "cust-1");

        String sent = capturePasswordSentToAuth0();

        // Auth0 does not echo the password, and nothing here adds it back.
        assertThat(created).doesNotContainKey("password");
        assertThat(created.toString()).doesNotContain(sent);
    }

    @Test
    @DisplayName("the password is never written to the logs")
    void passwordIsNeverLogged() {
        service.createDbUser("ada@example.com", "cust-1");

        String sent = capturePasswordSentToAuth0();

        List<ILoggingEvent> events = logAppender.list;
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                    .as("log line must not carry the generated password")
                    .doesNotContain(sent);
        }
    }

    private void swapRestTemplate(RestTemplate replacement) {
        try {
            Field rt = Auth0UserService.class.getDeclaredField("rt");
            rt.setAccessible(true);
            rt.set(service, replacement);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
