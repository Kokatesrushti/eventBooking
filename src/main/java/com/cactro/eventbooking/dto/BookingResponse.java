package com.cactro.eventbooking.dto;

import com.cactro.eventbooking.domain.Booking;

import java.time.Instant;

public record BookingResponse(Long id, Long eventId, Long customerId, int quantity, String status, Instant createdAt) {
    public static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(), b.getEventId(), b.getCustomerId(), b.getQuantity(), b.getStatus(), b.getCreatedAt());
    }
}
