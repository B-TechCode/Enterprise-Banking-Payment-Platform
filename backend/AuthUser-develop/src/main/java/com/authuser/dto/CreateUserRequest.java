package com.authuser.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * What a caller asks this service to provision.
 *
 * <p>Deliberately carries no password. Accepting one would let any caller choose
 * the credential a customer is created with, and the caller that used to supply
 * it sent the same literal every time. The initial password is generated here
 * instead, by {@link com.authuser.service.InitialPasswordGenerator}.</p>
 */
@Data
public class CreateUserRequest {

    /**
     * Validated here so a malformed address is refused as this service's own
     * 400, naming the field. Without it the first thing to notice would be
     * Auth0, whose 400 cannot be told apart from one caused by a generated
     * password the tenant policy rejects - a fault on our side, which must not
     * be reported as a fault in the request.
     */
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    private String email;

    @NotBlank(message = "customerId is required")
    private String customerId;
}
