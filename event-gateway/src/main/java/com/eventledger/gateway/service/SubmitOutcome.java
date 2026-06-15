package com.eventledger.gateway.service;

/**
 * How a {@code POST /events} submission resolved.
 *
 * <ul>
 *   <li>{@code CREATED}  — new event, applied to the Account Service (HTTP 201).</li>
 *   <li>{@code DUPLICATE} — already applied; original returned unchanged (HTTP 200).</li>
 *   <li>{@code DEGRADED} — stored locally but the Account Service was unavailable,
 *       so the transaction was not applied (HTTP 503). Retryable.</li>
 * </ul>
 */
public enum SubmitOutcome {
    CREATED,
    DUPLICATE,
    DEGRADED
}
