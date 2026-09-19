package com.digitalbank.aicommerce.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A biller as Biller Service returns it.
 *
 * <p>Declared here rather than shared, because Biller Service keeps its DTOs in
 * its own module and the agent is a client of its REST API, not of its code.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BillerView(
        String id,
        String name,
        String referenceNumber,
        String category,
        String status) {

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
