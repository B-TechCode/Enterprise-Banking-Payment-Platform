package com.digitalbank.aicommerce.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One page of billers. Biller Service paginates its list endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BillerPage(
        List<BillerView> items,
        long total,
        int limit,
        int offset) {

    public List<BillerView> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }
}
