package com.cactro.eventbooking.service;

import com.cactro.eventbooking.domain.Booking;
import com.cactro.eventbooking.domain.Event;
import com.cactro.eventbooking.domain.User;
import com.cactro.eventbooking.dto.BookingResponse;
import com.cactro.eventbooking.exception.ConflictException;
import com.cactro.eventbooking.exception.NotFoundException;
import com.cactro.eventbooking.exception.SoldOutException;
import com.cactro.eventbooking.repo.BookingRepository;
import com.cactro.eventbooking.repo.EventRepository;
import com.cactro.eventbooking.repo.UserRepository;
import com.cactro.eventbooking.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BookingService {
    private final EventRepository eventRepo;
    private final BookingRepository bookingRepo;
    private final UserRepository userRepo;
    private final JobService jobService;
    private final EmailService emailService;

    @Value("${app.mode}")
    private String mode;

    @Transactional
    public BookingResponse book(Long eventId, AuthUser user, int qty, String idemKey) {
        if (idemKey != null && !idemKey.isBlank()) {
            var existing = bookingRepo.findByIdempotencyKey(idemKey);
            if (existing.isPresent()) {                       // client retry: return the original result, don't double book
                Booking b = existing.get();
                if (!b.getCustomerId().equals(user.id()) || !b.getEventId().equals(eventId))
                    throw new ConflictException("Idempotency-Key already used for a different request");
                return BookingResponse.from(b);
            }
        } else {
            idemKey = null;
        }
        Booking b = "naive".equalsIgnoreCase(mode)
                ? bookNaive(eventId, user.id(), qty, idemKey)
                : bookOptimized(eventId, user.id(), qty, idemKey);
        return BookingResponse.from(b);
    }

    /** v1 "first LLM draft": read-modify-write on the hot row + synchronous email inside the transaction.
     *  Problems: lost updates (overselling), DB connection held during a slow HTTP call, pool exhaustion. */
    private Booking bookNaive(Long eventId, Long customerId, int qty, String idemKey) {
        Event e = eventRepo.findById(eventId).orElseThrow(() -> new NotFoundException("Event not found"));
        if (!Event.ACTIVE.equals(e.getStatus())) throw new ConflictException("Event is not open for booking");
        if (e.getAvailableSeats() < qty) throw new SoldOutException();
        e.setAvailableSeats(e.getAvailableSeats() - qty);
        eventRepo.save(e);
        Booking b = new Booking();
        b.setEventId(eventId);
        b.setCustomerId(customerId);
        b.setQuantity(qty);
        b.setIdempotencyKey(idemKey);
        b = bookingRepo.save(b);
        User u = userRepo.findById(customerId).orElseThrow();
        emailService.sendBookingConfirmation(b, e, u);        // slow external call while holding a DB connection
        b.setEmailSent(true);
        return bookingRepo.save(b);
    }

    /** Final design: one atomic UPDATE (check + decrement), one INSERT booking, one INSERT job (outbox). */
    private Booking bookOptimized(Long eventId, Long customerId, int qty, String idemKey) {
        int updated = eventRepo.decrementSeats(eventId, qty);
        if (updated == 0) {
            Event e = eventRepo.findById(eventId).orElseThrow(() -> new NotFoundException("Event not found"));
            if (!Event.ACTIVE.equals(e.getStatus())) throw new ConflictException("Event is not open for booking");
            throw new SoldOutException();
        }
        Booking b = new Booking();
        b.setEventId(eventId);
        b.setCustomerId(customerId);
        b.setQuantity(qty);
        b.setIdempotencyKey(idemKey);
        b = bookingRepo.save(b);
        jobService.enqueue("BOOKING_CONFIRMATION", Map.of("bookingId", b.getId()));
        return b;
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> mine(Long customerId) {
        return bookingRepo.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(BookingResponse::from).toList();
    }

    @Transactional
    public BookingResponse cancel(Long bookingId, Long customerId) {
        Booking b = bookingRepo.findById(bookingId).orElseThrow(() -> new NotFoundException("Booking not found"));
        if (!b.getCustomerId().equals(customerId)) throw new AccessDeniedException("Not your booking");
        if (Booking.CANCELLED.equals(b.getStatus())) return BookingResponse.from(b);
        b.setStatus(Booking.CANCELLED);
        bookingRepo.save(b);
        eventRepo.incrementSeats(b.getEventId(), b.getQuantity());
        return BookingResponse.from(b);
    }
}
