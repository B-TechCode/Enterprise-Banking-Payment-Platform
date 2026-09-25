package com.digitalbank.customerservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * What this service asks the identity provider to create.
 *
 * <p>Deliberately carries no password. An initial credential is the identity
 * provider's concern, and AuthUser generates one that nothing here ever sees.
 * This service previously sent the literal "default-password" for every
 * customer, which made one shared credential out of every account.</p>
 */
@Data
@AllArgsConstructor
public class CustomerRegistrationRequest {
    private String email;
    private String customerId; // Link to customer-service record
}
