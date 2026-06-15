package com.eventledger.gateway.service;

/** Raised when an event id is not present in the Gateway's local store (HTTP 404). */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {
        super("No event found with id " + eventId);
    }
}
