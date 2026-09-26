package com.authuser.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.commons.exception.ConflictException;
import com.commons.exception.UpstreamException;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Service responsible for interacting with the Auth0 Management API.
 *
 * <p>This class encapsulates all calls to Auth0's Management API for:
 * <ul>
 *   <li>Creating new database (Username-Password-Authentication) users</li>
 *   <li>Assigning roles to users after creation</li>
 * </ul>
 *
 * <p>By centralizing Auth0 API calls here, we ensure that:
 * <ul>
 *   <li>Only this service knows about the Management API credentials.</li>
 *   <li>All microservices can delegate identity provisioning to this service instead of calling Auth0 directly.</li>
 *   <li>Our code follows the principle of least privilege — one service holds admin capabilities.</li>
 * </ul>
 */
@Service
@Slf4j
public class Auth0UserService {

    /**
     * Auth0 domain (tenant URL), e.g. https://dev-wgk04dj5v68sbhre.us.auth0.com
     * Configured in application.yml as auth0.domain
     */
    @Value("${auth0.domain}")
    private String domain;

    /**
     * The role every provisioned customer is given.
     *
     * <p>Role ids are generated per tenant, so a literal here is correct in one
     * tenant and quietly wrong in every other - and a wrong one cannot be
     * corrected without a release. Empty by default, matching the other Auth0
     * settings: the service starts without it and refuses the call instead.</p>
     */
    @Value("${auth0.role-id:}")
    private String roleId;

    private final ManagementTokenService tokens;
    private final InitialPasswordGenerator passwords;
    private final RestTemplate rt = new RestTemplate();

    /**
     * Constructor injection of the ManagementTokenService, which is responsible for
     * obtaining a valid Management API access token using the Client Credentials flow.
     *
     * @param tokens service that provides the "Bearer" token for Auth0 Management API calls
     */
    public Auth0UserService(ManagementTokenService tokens, InitialPasswordGenerator passwords) {
        this.tokens = tokens;
        this.passwords = passwords;
    }

    /**
     * Creates a new user in Auth0's "Username-Password-Authentication" database connection.
     *
     * <p>This is typically called after a customer completes KYC in CustomerService.
     * The method uses the Management API to provision the user with an email, password,
     * username (we map to customerId), and optional custom metadata.</p>
     *
     * <h3>HTTP Request</h3>
     * POST {domain}/api/v2/users
     *
     * <p>The initial password is generated here and deliberately not accepted
     * from the caller. It is sent to Auth0 once and never logged, returned or
     * retained, so the created user has no usable password until one is set
     * through a password-change ticket (backlog item 15).</p>
     *
     * @param email       the user's email
     * @param customerId  external customer ID (used as username and stored in app_metadata)
     * @return a {@link Map} containing Auth0's created user object (user_id, email, etc.)
     */
    public Map createDbUser(String email, String customerId) {
        // Before anything is created. A user provisioned without a role can
        // sign in and do nothing, and there is no way to tell that apart from a
        // permissions bug by looking at them. If this deployment cannot say
        // which role to grant, it has no business creating the user at all.
        if (roleId == null || roleId.isBlank()) {
            log.error("auth0.role-id is not set (AUTH0_CUSTOMER_ROLE_ID); refusing to provision a "
                    + "user this deployment could not authorise");
            throw new UpstreamException("This platform is not configured to provision users right now");
        }

        // 1️⃣ Retrieve the Management API bearer token
        String auth = tokens.getBearer();

        // 2️⃣ Set up HTTP headers
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", auth);

        // 3️⃣ Build the request body for the new user
        Map<String, Object> body = new HashMap<>();
        List<String> roles =new ArrayList<String>();
       
        body.put("email", email);

        // Generated per user, used once, never held. Nothing in this platform
        // can tell you what it was.
        body.put("password", passwords.generate());
        body.put("username", customerId); // we use customerId as username
        body.put("connection", "Username-Password-Authentication");
        
        // 4️⃣ Include custom metadata (e.g., customer_id) to link Auth0 user back to our domain model
        Map<String, Object> appMeta = new HashMap<>();
        appMeta.put("customer_id", customerId);

        body.put("app_metadata", appMeta);

        // Call Auth0's Management API to create the user.
        //
        // A non-2xx answer makes RestTemplate throw rather than return, so
        // there is no status left to inspect afterwards - the check that used
        // to sit below this call could never run. Translating here is what
        // lets the status Auth0 gave survive the hop, instead of becoming a
        // 500 of ours that says nothing.
        ResponseEntity<Map> resp;
        try {
            resp = rt.postForEntity(
                    domain + "/api/v2/users",
                    new HttpEntity<>(body, h),
                    Map.class
            );
        } catch (HttpStatusCodeException e) {
            throw translate(e);
        }

        Map createdUser = resp.getBody();
        String userId = (String) createdUser.get("user_id"); // e.g. auth0|abc123
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("Auth0 user created but user_id missing");
        }

        // 👇 Straight role assignment — that’s it.
        // Creating the user and granting its role are two calls, so this
        // method can fail half way. What it must not do is return as though it
        // succeeded, or leave the half behind it standing.
        assignRoleOrUndoCreation(userId, auth);
        
        
        // ✅ Return the created user object
        return resp.getBody();
    }


    /**
     * Turns a refusal from the Auth0 Management API into the refusal this
     * service should report.
     *
     * <p>Only 409 is about the caller. Auth0 answers 401 when our management
     * token is rejected and 403 when the M2M application lacks
     * {@code create:users} - both describe this platform's own credentials,
     * not the caller's. Passing either through would blame a caller for a
     * tenant misconfiguration they cannot see, let alone fix, so both become
     * 502s: this service could not complete the call.</p>
     *
     * <p>That is where this differs from the Feign decoders in CustomerService
     * and PaymentOrchestrator, which do pass 403 through. There a downstream
     * 403 genuinely meant the caller was refused. Here it never does. AuthUser
     * still answers a 403 of its own when a caller lacks
     * {@code admin:users.write}, but that comes from the security layer above,
     * not from Auth0.</p>
     *
     * <p>A 400 should no longer be reachable: the only caller-supplied value
     * is the email, and it is validated before the call. Anything still
     * arriving as a 400 is a defect on this side - most likely a generated
     * password the tenant policy refuses - so it is logged at error and
     * reported as a 502 rather than blamed on the request.</p>
     *
     * <p>No part of Auth0's response body is carried into the message. It
     * names the tenant and the connection, and that has no business reaching a
     * caller of this platform.</p>
     */
    private RuntimeException translate(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        log.warn("Auth0 Management API answered {} for user creation", status);

        return switch (status) {
            case 409 -> new ConflictException(
                    "That email address or customer is already registered");
            case 400 -> {
                log.error("Auth0 refused the user creation request as malformed; the email was "
                        + "validated before the call, so this is a defect on our side");
                yield new UpstreamException("The user could not be created right now");
            }
            default -> new UpstreamException("The user could not be created right now");
        };
    }

    /**
     * Grants the configured role, and removes the user again if that fails.
     *
     * <p>A user created without a role is worse than no user at all: they
     * authenticate successfully and are then refused everything, which reads as
     * a permissions bug rather than a provisioning one. Worse, the caller
     * retrying now gets a 409 - correctly, since the user does exist - so the
     * failure becomes permanent and can only be cleared by hand in the tenant.
     * That is a consequence of item 12 making 409 meaningful, and it is why
     * this undoes the creation rather than simply reporting it.</p>
     *
     * <p>The compensation is narrow on purpose. This user was created moments
     * ago in this same call and nothing else can have referred to it yet, so
     * removing it returns the tenant to where it started. Assigning a role is
     * idempotent in Auth0, so a partial success costs nothing either.</p>
     */
    private void assignRoleOrUndoCreation(String userId, String bearer) {
        try {
            assignRole(userId, roleId, bearer);
        } catch (RestClientException e) {
            int status = (e instanceof HttpStatusCodeException h) ? h.getStatusCode().value() : 0;
            log.error("Auth0 refused to grant the role (status {}); removing the user just created", status);

            deleteUser(userId, bearer);
            throw new UpstreamException("The user could not be created right now");
        }
    }

    /**
     * Removes a user that was created but could not be granted its role.
     *
     * <p>If this fails too, the user stays in the tenant with no role. Nothing
     * more can be done about it from here, so the one useful thing is to say
     * which user it is: an orphan named in the log can be found and removed,
     * and an orphan nobody logged cannot.</p>
     *
     * <p>Requires {@code delete:users} on the Management API application. Without
     * that scope this call is refused and the orphan is logged instead.</p>
     */
    private void deleteUser(String userId, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", bearer);

        try {
            rt.exchange(domain + "/api/v2/users/" + userId,
                    HttpMethod.DELETE, new HttpEntity<>(h), Void.class);
            log.warn("Removed the partly provisioned user {}", userId);
        } catch (RestClientException e) {
            log.error("Could not remove the partly provisioned user {}; it exists in the tenant "
                    + "with no role and has to be removed by hand", userId);
        }
    }

    private void assignRole(String userId, String roleId, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", bearer);

        Map<String, Object> body = Map.of("roles", List.of(roleId));

        String url = domain + "/api/v2/users/" + userId + "/roles"; // ✅ use raw user_id e.g. auth0|xxxxx

        rt.postForEntity(
            url,
            new HttpEntity<>(body, h),
            Void.class
        );
    }
    
    
}
