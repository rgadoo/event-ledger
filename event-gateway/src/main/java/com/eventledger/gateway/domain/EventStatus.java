package com.eventledger.gateway.domain;

/**
 * Lifecycle of an event as seen by the Gateway.
 *
 * <ul>
 *   <li>{@code RECEIVED} — stored locally, not yet applied to the Account Service.</li>
 *   <li>{@code APPLIED}  — successfully applied to the Account Service.</li>
 *   <li>{@code FAILED}   — the Account Service call failed; the event is retryable
 *       (the Account Service is idempotent on eventId, so a retry is always safe).</li>
 * </ul>
 */
public enum EventStatus {
    RECEIVED,
    APPLIED,
    FAILED
}
