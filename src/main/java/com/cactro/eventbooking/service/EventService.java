package com.cactro.eventbooking.service;

import com.cactro.eventbooking.domain.Booking;
import com.cactro.eventbooking.domain.Event;
import com.cactro.eventbooking.domain.User;
import com.cactro.eventbooking.dto.BookingResponse;
import com.cactro.eventbooking.dto.EventRequest;
import com.cactro.eventbooking.dto.EventResponse;
import com.cactro.eventbooking.dto.PageResponse;
import com.cactro.eventbooking.exception.ConflictException;
import com.cactro.eventbooking.exception.NotFoundException;
import com.cactro.eventbooking.repo.BookingRepository;
import com.cactro.eventbooking.repo.EventRepository;
import com.cactro.eventbooking.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EventService {
    private final EventRepository eventRepo;
    private final BookingRepository bookingRepo;
    private final UserRepository userRepo;
    private final JobService jobService;
    private final EmailService emailService;

    @Value("${app.mode}")
    private String mode;

    @Transactional
    public EventResponse create(Long organizerId, EventRequest r) {
        Event e = new Event();
        e.setOrganizerId(organizerId);
        apply(e, r);
        e.setAvailableSeats(r.totalSeats());
        return EventResponse.from(eventRepo.save(e));
    }

    @Transactional
    public EventResponse update(Long id, Long organizerId, EventRequest r) {
        // pessimistic lock: serialises with bookings' atomic UPDATE, so we never write back a stale available_seats
        Event e = eventRepo.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Event not found"));
        requireOwner(e, organizerId);
        if (!Event.ACTIVE.equals(e.getStatus())) throw new ConflictException("Event is cancelled");
        int booked = e.getTotalSeats() - e.getAvailableSeats();
        if (r.totalSeats() < booked)
            throw new ConflictException("totalSeats cannot be below already booked seats (" + booked + ")");

        List<String> changes = new ArrayList<>();
        if (!Objects.equals(e.getTitle(), r.title())) changes.add("title");
        if (!Objects.equals(e.getDescription(), r.description())) changes.add("description");
        if (!Objects.equals(e.getVenue(), r.venue())) changes.add("venue");
        if (!e.getStartTime().truncatedTo(ChronoUnit.SECONDS).equals(r.startTime().truncatedTo(ChronoUnit.SECONDS)))
            changes.add("start time");
        if (e.getPrice().compareTo(r.price()) != 0) changes.add("price");

        apply(e, r);
        e.setAvailableSeats(r.totalSeats() - booked);
        e.setUpdatedAt(Instant.now());
        eventRepo.save(e);

        if (!changes.isEmpty()) notifyBookedCustomers(e, "Updated: " + String.join(", ", changes));
        return EventResponse.from(e);
    }

    @Transactional
    public EventResponse cancel(Long id, Long organizerId) {
        Event e = eventRepo.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Event not found"));
        requireOwner(e, organizerId);
        if (Event.CANCELLED.equals(e.getStatus())) return EventResponse.from(e);
        e.setStatus(Event.CANCELLED);
        e.setUpdatedAt(Instant.now());
        eventRepo.save(e);
        notifyBookedCustomers(e, "This event has been cancelled by the organizer.");
        return EventResponse.from(e);
    }

    /** naive: loop and send emails inside the request. optimized: one outbox row, worker fans out. */
    private void notifyBookedCustomers(Event e, String summary) {
        if ("naive".equalsIgnoreCase(mode)) {
            List<Long> ids = bookingRepo.findCustomerIdsForEvent(e.getId());
            for (User u : userRepo.findAllById(ids)) emailService.sendEventUpdate(e, u, summary);
        } else {
            jobService.enqueue("EVENT_UPDATED", Map.of("eventId", e.getId(), "summary", summary));
        }
    }

    @Transactional(readOnly = true)
    public EventResponse get(Long id) {
        return eventRepo.findById(id).map(EventResponse::from).orElseThrow(() -> new NotFoundException("Event not found"));
    }

    @Transactional(readOnly = true)
    public PageResponse<EventResponse> list(int page, int size) {
        var pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("startTime"));
        return PageResponse.from(eventRepo.findByStatus(Event.ACTIVE, pr).map(EventResponse::from));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> mine(Long organizerId) {
        return eventRepo.findByOrganizerIdOrderByStartTimeAsc(organizerId).stream().map(EventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> bookingsForEvent(Long eventId, Long organizerId) {
        Event e = eventRepo.findById(eventId).orElseThrow(() -> new NotFoundException("Event not found"));
        requireOwner(e, organizerId);
        return bookingRepo.findByEventIdOrderByCreatedAtDesc(eventId).stream().map(BookingResponse::from).toList();
    }

    private void requireOwner(Event e, Long organizerId) {
        if (!e.getOrganizerId().equals(organizerId)) throw new AccessDeniedException("You do not own this event");
    }

    private void apply(Event e, EventRequest r) {
        e.setTitle(r.title());
        e.setDescription(r.description());
        e.setVenue(r.venue());
        e.setStartTime(r.startTime());
        e.setTotalSeats(r.totalSeats());
        e.setPrice(r.price());
    }
}
