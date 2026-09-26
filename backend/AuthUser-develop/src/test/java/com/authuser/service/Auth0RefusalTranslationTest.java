package com.authuser.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.UpstreamException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A refusal from Auth0 keeps its meaning instead of becoming a 500 of ours.
 *
 * <p>{@code RestTemplate} throws on any non-2xx, and the shared
 * {@code GlobalExceptionHandler} has no handler for its exceptions, so every
 * answer Auth0 gave used to reach the catch-all and come back as
 * {@code 500 INTERNAL_ERROR} from AuthUser itself. A duplicate registration, a
 * rejected management token and an unreachable tenant were indistinguishable.</p>
 *
 * <p>The mapping is deliberately not the one the Feign decoders use. Only 409
 * describes the caller. Auth0 answers 401 and 403 about <em>this platform's</em>
 * credentials, so those become 502s rather than being passed through - a caller
 * cannot act on a tenant misconfiguration, and telling them they are forbidden
 * would be a lie about whose fault it is.</p>
 */
class Auth0RefusalTranslationTest {

    /**
     * Shaped like a real Auth0 Management API error. The tenant, connection and
     * internal error code are present on purpose: they are what must not escape.
     */
    private static final String AUTH0_BODY = """
            {"statusCode":409,"error":"Conflict",\
            "message":"The user already exists.",\
            "errorCode":"auth0_idp_error",\
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

        // Same reach-in as Auth0UserServicePasswordTest: the RestTemplate is a
        // field initialiser rather than a constructor dependency, and reshaping
        // that is not this item's business.
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

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
    }

    /** Makes the user-creation call fail the way Auth0 would, with the given status. */
    private void auth0Answers(HttpStatusCodeException failure) {
        when(restTemplate.postForEntity(contains("/api/v2/users"), any(), eq(Map.class)))
                .thenThrow(failure);
    }

    private static HttpStatusCodeException clientError(HttpStatus status) {
        return HttpClientErrorException.create(
                status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                AUTH0_BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private Throwable callAndCatch() {
        return org.assertj.core.api.Assertions
                .catchThrowable(() -> service.createDbUser("ada@example.com", "cust-1"));
    }

    // ------------------------------------------------------------------ 409

    @Test
    @DisplayName("a duplicate user is a conflict, not a server error")
    void duplicateUserBecomesConflict() {
        auth0Answers(clientError(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.createDbUser("ada@example.com", "cust-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the conflict says what is already taken, in this platform's own words")
    void conflictMessageIsOurOwn() {
        auth0Answers(clientError(HttpStatus.CONFLICT));

        assertThat(callAndCatch())
                .hasMessageContaining("already registered")
                // username is the customerId since item 13, so either can collide
                .hasMessageContaining("customer");
    }

    // ------------------------------------------- statuses about us, not the caller

    @Test
    @DisplayName("Auth0 refusing our management token is not the caller's fault")
    void auth0UnauthorizedIsNotTheCallersProblem() {
        auth0Answers(clientError(HttpStatus.UNAUTHORIZED));

        // A 401 here means the token this service holds was rejected. Passing it
        // through would tell a caller their own credentials failed, sending them
        // to re-authenticate against a problem no login of theirs can fix.
        assertThat(callAndCatch()).isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("our M2M application lacking a scope is not the caller's fault")
    void auth0ForbiddenIsNotTheCallersProblem() {
        auth0Answers(clientError(HttpStatus.FORBIDDEN));

        // The Feign decoders in CustomerService and PaymentOrchestrator do pass
        // 403 through, because there it genuinely described the caller. Here it
        // describes our M2M application missing create:users. AuthUser still
        // answers a real 403 of its own from @PreAuthorize - that one is the
        // caller's, and it never reaches this method.
        Throwable thrown = callAndCatch();
        assertThat(thrown).isInstanceOf(UpstreamException.class);
        assertThat(thrown).isNotInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a tenant rate limit is reported as upstream, not as a bad request")
    void rateLimitIsUpstream() {
        auth0Answers(clientError(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(callAndCatch()).isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("an Auth0 outage is reported as upstream")
    void serverErrorIsUpstream() {
        auth0Answers(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThat(callAndCatch()).isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("a 400 from Auth0 is our defect, so it is not blamed on the request")
    void malformedRequestIsOursNotTheCallers() {
        auth0Answers(clientError(HttpStatus.BAD_REQUEST));

        // The only caller-supplied value is the email, and it is validated
        // before the call. A surviving 400 means something we generated was
        // refused - most likely the password, against tenant policy - so it
        // must not come back as though the caller sent something wrong.
        assertThat(callAndCatch()).isInstanceOf(UpstreamException.class);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("defect on our side"));
    }

    // ------------------------------------------------------------------ leakage

    @Test
    @DisplayName("Auth0's error body reaches neither the caller nor the log")
    void auth0ErrorBodyNeverReachesTheCaller() {
        auth0Answers(clientError(HttpStatus.CONFLICT));

        Throwable thrown = callAndCatch();

        // The tenant name, the connection name and Auth0's internal error code
        // are all infrastructure detail. A caller of this platform has no use
        // for them and no business knowing them.
        assertThat(thrown.getMessage())
                .doesNotContain("dev-wgk04dj5v68sbhre")
                .doesNotContain("Username-Password-Authentication")
                .doesNotContain("auth0_idp_error");

        String logged = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(logged)
                .doesNotContain("dev-wgk04dj5v68sbhre")
                .doesNotContain("auth0_idp_error");

        // The status is worth keeping: it is what an operator needs to tell a
        // duplicate apart from an outage when reading the log after the fact.
        assertThat(logged).contains("409");
    }

    // ------------------------------------------------------------------ happy path

    @Test
    @DisplayName("a successful creation is unchanged")
    @SuppressWarnings("unchecked")
    void successfulCreationIsUnchanged() {
        when(restTemplate.postForEntity(contains("/api/v2/users"), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("user_id", "auth0|abc123", "email", "ada@example.com"),
                        HttpStatus.CREATED));
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        Map created = service.createDbUser("ada@example.com", "cust-1");

        assertThat(created).containsEntry("user_id", "auth0|abc123");
    }
}
