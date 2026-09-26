package com.authuser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.commons.exception.UpstreamException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Provisioning a customer either finishes or leaves nothing behind.
 *
 * <p>Creating the user and granting its role are two calls to Auth0, so the
 * method can fail half way. The half-provisioned outcome is the worst of the
 * three: the customer authenticates successfully and is then refused
 * everything, which reads as a permissions bug rather than a provisioning one,
 * and nothing in this platform records that it happened.</p>
 *
 * <p>It also became permanent once item 12 landed. A caller retrying now gets a
 * truthful 409, because the user really does exist - so the failure can only be
 * cleared by hand in the tenant. That is why a failed role assignment undoes
 * the creation rather than merely reporting it.</p>
 *
 * <p>The role id itself comes from configuration. Role ids are generated per
 * tenant, so the literal that used to sit here was correct in exactly one
 * tenant and silently wrong in every other.</p>
 */
class Auth0RoleProvisioningTest {

    private static final String DOMAIN = "https://tenant.example.com";
    private static final String ROLE = "rol_configured123";
    private static final String USER_ID = "auth0|abc123";

    private static final String AUTH0_BODY = """
            {"statusCode":403,"error":"Forbidden",\
            "message":"Insufficient scope",\
            "connection":"Username-Password-Authentication",\
            "tenant":"dev-wgk04dj5v68sbhre"}""";

    private RestTemplate restTemplate;
    private Auth0UserService service;

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        ManagementTokenService tokens = mock(ManagementTokenService.class);
        when(tokens.getBearer()).thenReturn("Bearer management-token");

        service = new Auth0UserService(tokens, new InitialPasswordGenerator());

        restTemplate = mock(RestTemplate.class);
        set("rt", restTemplate);
        set("domain", DOMAIN);
        set("roleId", ROLE);

        // The user is created successfully; each test decides what the role
        // call then does.
        when(restTemplate.postForEntity(contains("/api/v2/users"), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("user_id", USER_ID, "email", "ada@example.com"),
                        HttpStatus.CREATED));

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    private void set(String field, Object value) throws Exception {
        Field f = Auth0UserService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
    }

    private void roleAssignmentFails() {
        when(restTemplate.postForEntity(contains("/roles"), any(), eq(Void.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                        AUTH0_BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    private void roleAssignmentSucceeds() {
        when(restTemplate.postForEntity(contains("/roles"), any(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));
    }

    private String logged() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ------------------------------------------------------------- reporting

    @Test
    @DisplayName("a role that cannot be granted is an upstream failure, not a 500")
    void roleFailureIsReportedAsUpstream() {
        roleAssignmentFails();

        assertThatThrownBy(() -> service.createDbUser("ada@example.com", "cust-1"))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("a half-provisioned user is never reported as a success")
    void createDbUserDoesNotReportSuccessWhenRoleFailed() {
        roleAssignmentFails();

        // The contract that was missing: createDbUser used to return the created
        // user whether or not the role was attached, so a caller marking the
        // customer active had no way to know the provisioning was incomplete.
        assertThat(catchThrowable(() -> service.createDbUser("ada@example.com", "cust-1")))
                .isInstanceOf(UpstreamException.class);
    }

    // ------------------------------------------------------------ compensation

    @Test
    @DisplayName("the user created moments earlier is removed again")
    void orphanedUserIsDeleted() {
        roleAssignmentFails();

        catchThrowable(() -> service.createDbUser("ada@example.com", "cust-1"));

        verify(restTemplate).exchange(
                eq(DOMAIN + "/api/v2/users/" + USER_ID),
                eq(HttpMethod.DELETE), any(), eq(Void.class));
    }

    @Test
    @DisplayName("when the cleanup also fails, the orphan is named so it can be found")
    void failedCompensationLeavesTheOrphanFindable() {
        roleAssignmentFails();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("connection reset"));

        // Nothing more can be done from here, so the one useful act is to say
        // which user it is. An orphan named in the log can be removed by hand;
        // an orphan nobody logged cannot.
        assertThat(catchThrowable(() -> service.createDbUser("ada@example.com", "cust-1")))
                .isInstanceOf(UpstreamException.class);

        assertThat(logged()).contains(USER_ID);
        assertThat(logged()).contains("removed by hand");
    }

    @Test
    @DisplayName("a successful provisioning deletes nothing")
    @SuppressWarnings("unchecked")
    void successfulProvisioningDeletesNothing() {
        roleAssignmentSucceeds();

        Map created = service.createDbUser("ada@example.com", "cust-1");

        assertThat(created).containsEntry("user_id", USER_ID);
        verify(restTemplate, never())
                .exchange(anyString(), eq(HttpMethod.DELETE), any(), eq(Void.class));
    }

    // ------------------------------------------------------------- the role id

    @Test
    @DisplayName("the role granted is the configured one, not a literal")
    @SuppressWarnings("unchecked")
    void roleIdComesFromConfiguration() {
        roleAssignmentSucceeds();

        service.createDbUser("ada@example.com", "cust-1");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(contains("/roles"), captor.capture(), eq(Void.class));

        assertThat((List<String>) captor.getValue().getBody().get("roles"))
                .containsExactly(ROLE);
    }

    @Test
    @DisplayName("the role is granted to the user just created")
    void roleIsAssignedToTheUserJustCreated() {
        roleAssignmentSucceeds();

        service.createDbUser("ada@example.com", "cust-1");

        verify(restTemplate).postForEntity(
                eq(DOMAIN + "/api/v2/users/" + USER_ID + "/roles"), any(), eq(Void.class));
    }

    @Test
    @DisplayName("with no role configured, no user is created at all")
    void missingRoleIdIsRefusedBeforeCallingAuth0() throws Exception {
        set("roleId", "");

        assertThatThrownBy(() -> service.createDbUser("ada@example.com", "cust-1"))
                .isInstanceOf(UpstreamException.class);

        // The order is the point. Checking after creation would orphan a user on
        // every single call in a misconfigured deployment - the fault this item
        // exists to remove, reintroduced by a deployment mistake.
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(Map.class));

        assertThat(logged()).contains("AUTH0_CUSTOMER_ROLE_ID");
    }

    // ------------------------------------------------------------- leakage

    @Test
    @DisplayName("a role failure does not carry tenant detail out with it")
    void tenantDetailDoesNotEscapeARoleFailure() {
        roleAssignmentFails();

        Throwable thrown = catchThrowable(() -> service.createDbUser("ada@example.com", "cust-1"));

        // The exact message, not the absence of particular words. Spring's
        // HttpStatusCodeException.getMessage() is only "403 Forbidden" - the
        // response body lives in getResponseBodyAsString() - so asserting what
        // this message does not contain could never fail, however much of the
        // exception were appended to it. Pinning the whole string does.
        assertThat(thrown.getMessage())
                .isEqualTo("The user could not be created right now");

        assertThat(logged()).doesNotContain("dev-wgk04dj5v68sbhre");

        // The status is kept: it is what separates a missing scope from an outage.
        assertThat(logged()).contains("403");
    }
}
