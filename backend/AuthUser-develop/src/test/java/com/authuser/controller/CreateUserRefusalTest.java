package com.authuser.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.authuser.service.Auth0UserService;
import com.commons.exception.ConflictException;
import com.commons.exception.GlobalExceptionHandler;
import com.commons.exception.UpstreamException;

/**
 * What a caller of {@code POST /api/v1/iam/users} actually receives.
 *
 * <p>The translation in {@code Auth0UserService} is only worth anything if the
 * status survives the whole hop. This drives the controller through the shared
 * exception handler and asserts on the response itself, because the bug being
 * fixed lived precisely in the gap between the two: the exception was raised
 * correctly and then flattened to a 500 on the way out.</p>
 *
 * <p>This is also what makes backlog item 11 reachable. CustomerService already
 * maps a 409 from AuthUser to "that customer is already registered in the
 * identity provider", but AuthUser never sent a 409, so that branch could not
 * fire. The first test here is the missing link in that chain.</p>
 */
class CreateUserRefusalTest {

    private Auth0UserService auth0;
    private MockMvc mvc;

    private static final String BODY = """
            {"email":"ada@example.com","customerId":"cust-1"}""";

    @BeforeEach
    void setUp() {
        auth0 = mock(Auth0UserService.class);

        // Standalone rather than a full context: this service needs a Config
        // Server to start, and the question here is only how an exception
        // becomes a response.
        mvc = MockMvcBuilders.standaloneSetup(new CreateUserController(auth0))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a duplicate registration answers 409, which is what item 11 was waiting for")
    void conflictIsReportedAs409() throws Exception {
        when(auth0.createDbUser(anyString(), anyString()))
                .thenThrow(new ConflictException("That email address or customer is already registered"));

        mvc.perform(post("/api/v1/iam/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value(
                        "That email address or customer is already registered"));
    }

    @Test
    @DisplayName("a refusal that is really ours answers 502, not 500")
    void upstreamFailureIsReportedAs502() throws Exception {
        when(auth0.createDbUser(anyString(), anyString()))
                .thenThrow(new UpstreamException("The user could not be created right now"));

        // 500 would read as a bug in this request. 502 says the call out failed,
        // which is the difference between an operator retrying and an operator
        // raising a ticket.
        mvc.perform(post("/api/v1/iam/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("a malformed email is refused here, before Auth0 is troubled with it")
    void malformedEmailIsRefusedLocally() throws Exception {
        mvc.perform(post("/api/v1/iam/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-address","customerId":"cust-1"}"""))
                .andExpect(status().isBadRequest());

        // The point of validating locally: Auth0 answers 400 both for an
        // invalid email and for a password its policy rejects, and those two
        // have opposite owners. Catching the caller's mistake here leaves any
        // surviving 400 unambiguously ours.
        verify(auth0, never()).createDbUser(anyString(), anyString());
    }
}
