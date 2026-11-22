package com.cactro.eventbooking.service;

import com.cactro.eventbooking.domain.Booking;
import com.cactro.eventbooking.domain.Event;
import com.cactro.eventbooking.domain.Job;
import com.cactro.eventbooking.domain.User;
import com.cactro.eventbooking.repo.BookingRepository;
import com.cactro.eventbooking.repo.EventRepository;
import com.cactro.eventbooking.repo.JobRepository;
import com.cactro.eventbooking.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobHandlers {
    private final ObjectMapper mapper;
    private final BookingRepository bookingRepo;
    private final EventRepository eventRepo;
    private final UserRepository userRepo;
    private final JobRepository jobRepo;
    private final EmailService emailService;

    public void handle(Job job) throws Exception {
        JsonNode p = mapper.readTree(job.getPayload());
        switch (job.getType()) {
            case "BOOKING_CONFIRMATION" -> bookingConfirmation(p.get("bookingId").asLong());
            case "EVENT_UPDATED" -> {
                int n = jobRepo.fanOutEventUpdate(p.get("eventId").asLong(), p.get("summary").asText());
                log.info("Event {} update fanned out to {} customers", p.get("eventId").asLong(), n);
            }
            case "EVENT_UPDATE_EMAIL" -> eventUpdateEmail(p.get("eventId").asLong(),
                    p.get("customerId").asLong(), p.get("summary").asText());
            default -> throw new IllegalArgumentException("Unknown job type " + job.getType());
        }
    }

    private void bookingConfirmation(long bookingId) {
        Booking b = bookingRepo.findById(bookingId).orElse(null);
        if (b == null || b.isEmailSent()) return;               // idempotent: never email twice on retry
        Event e = eventRepo.findById(b.getEventId()).orElseThrow();
        User u = userRepo.findById(b.getCustomerId()).orElseThrow();
        emailService.sendBookingConfirmation(b, e, u);
        bookingRepo.markEmailSent(bookingId);
    }

    private void eventUpdateEmail(long eventId, long customerId, String summary) {
        Event e = eventRepo.findById(eventId).orElseThrow();
        User u = userRepo.findById(customerId).orElseThrow();
        emailService.sendEventUpdate(e, u, summary);
    }
}
