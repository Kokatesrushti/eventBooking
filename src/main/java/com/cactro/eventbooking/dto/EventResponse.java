package com.cactro.eventbooking.dto;

import com.cactro.eventbooking.domain.Event;

import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(Long id, Long organizerId, String title, String description, String venue,
                            Instant startTime, int totalSeats, int availableSeats, BigDecimal price,
                            String status) {
    public static EventResponse from(Event e) {
        return new EventResponse(e.getId(), e.getOrganizerId(), e.getTitle(), e.getDescription(), e.getVenue(),
                e.getStartTime(), e.getTotalSeats(), e.getAvailableSeats(), e.getPrice(), e.getStatus());
    }
}
