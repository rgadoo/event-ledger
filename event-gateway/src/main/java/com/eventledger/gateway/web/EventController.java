package com.eventledger.gateway.web;

import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.SubmitResult;
import com.eventledger.gateway.web.dto.EventResponse;
import com.eventledger.gateway.web.dto.SubmitEventRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Submit a transaction event.
     * <ul>
     *   <li>201 Created — new event applied</li>
     *   <li>200 OK — duplicate; original returned</li>
     *   <li>503 Service Unavailable — stored locally but Account Service is down (retryable)</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody SubmitEventRequest request) {
        SubmitResult result = eventService.submit(request);
        EventResponse body = EventResponse.from(result.record());
        HttpStatus status = switch (result.outcome()) {
            case CREATED -> HttpStatus.CREATED;
            case DUPLICATE -> HttpStatus.OK;
            case DEGRADED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{eventId}")
    public EventResponse getEvent(@PathVariable String eventId) {
        return EventResponse.from(eventService.getEvent(eventId));
    }

    /** List an account's events in chronological order (Gateway-local; always available). */
    @GetMapping
    public List<EventResponse> listByAccount(@RequestParam("account") String accountId) {
        return eventService.listByAccount(accountId).stream()
                .map(EventResponse::from)
                .toList();
    }
}
