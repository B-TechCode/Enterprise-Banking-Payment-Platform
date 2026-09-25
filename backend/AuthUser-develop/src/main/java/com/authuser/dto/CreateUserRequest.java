package com.authuser.dto;

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

    private String email;
    private String customerId;
}
