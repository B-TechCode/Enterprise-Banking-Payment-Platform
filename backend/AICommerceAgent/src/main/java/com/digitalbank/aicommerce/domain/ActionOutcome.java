package com.digitalbank.aicommerce.domain;

/**
 * How an agent action ended.
 */
public enum ActionOutcome {

    /** Completed and returned data. */
    SUCCESS,

    /** Refused before any downstream call, for example an unauthorized caller. */
    DENIED,

    /** Attempted but the downstream call failed. */
    ERROR
}
