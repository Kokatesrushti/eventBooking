package com.cactro.eventbooking.web;

import com.cactro.eventbooking.dto.*;
import com.cactro.eventbooking.security.AuthUser;
import com.cactro.eventbooking.service.BookingService;
import com.cactro.eventbooking.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {
    private final EventService eventService;
    private final BookingService bookingService;

    // ---------- organizer ----------
    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody EventRequest r) {
        return eventService.create(u.id(), r);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    public EventResponse update(@AuthenticationPrincipal AuthUser u, @PathVariable Long id,
                                @Valid @RequestBody EventRequest r) {
        return eventService.update(id, u.id(), r);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    public EventResponse cancel(@AuthenticationPrincipal AuthUser u, @PathVariable Long id) {
        return eventService.cancel(id, u.id());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('ORGANIZER')")
    public List<EventResponse> mine(@AuthenticationPrincipal AuthUser u) { return eventService.mine(u.id()); }

    @GetMapping("/{id}/bookings")
    @PreAuthorize("hasRole('ORGANIZER')")
    public List<BookingResponse> bookings(@AuthenticationPrincipal AuthUser u, @PathVariable Long id) {
        return eventService.bookingsForEvent(id, u.id());
    }

    // ---------- any authenticated user ----------
    @GetMapping
    public PageResponse<EventResponse> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return eventService.list(page, size);
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) { return eventService.get(id); }

    // ---------- customer ----------
    @PostMapping("/{id}/book")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse book(@AuthenticationPrincipal AuthUser u, @PathVariable Long id,
                                @Valid @RequestBody BookRequest r,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return bookingService.book(id, u, r.quantity(), idemKey);
    }
}
