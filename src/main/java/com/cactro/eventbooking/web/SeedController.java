package com.cactro.eventbooking.web;

import com.cactro.eventbooking.domain.Event;
import com.cactro.eventbooking.domain.Role;
import com.cactro.eventbooking.domain.User;
import com.cactro.eventbooking.repo.EventRepository;
import com.cactro.eventbooking.repo.UserRepository;
import com.cactro.eventbooking.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * LOAD-TEST HELPER ONLY (disabled unless SEED_ENABLED=true and the X-Seed-Secret header matches).
 * Creates 1 organizer, N customers (one shared BCrypt hash, computed once) and M events, and returns
 * pre-signed JWTs so k6 never touches /login (BCrypt would otherwise dominate the benchmark).
 * Safe to call repeatedly: existing users are reused, new events are created each call.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class SeedController {
    private final UserRepository userRepo;
    private final EventRepository eventRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    @Value("${app.seed.enabled}") private boolean enabled;
    @Value("${app.seed.secret}") private String secret;

    @PostMapping("/seed")
    @Transactional
    public Map<String, Object> seed(@RequestHeader("X-Seed-Secret") String provided,
                                    @RequestParam(defaultValue = "1000") int customers,
                                    @RequestParam(defaultValue = "1") int events,
                                    @RequestParam(defaultValue = "100000") int seats) {
        if (!enabled || secret == null || secret.isBlank() || !secret.equals(provided))
            throw new AccessDeniedException("Seeding disabled");

        User organizer = userRepo.findByEmail("seed-organizer@example.com").orElseGet(() -> {
            User o = new User();
            o.setName("Seed Organizer");
            o.setEmail("seed-organizer@example.com");
            o.setPasswordHash(encoder.encode("password123"));
            o.setRole(Role.ORGANIZER);
            return userRepo.save(o);
        });

        List<String> emails = new ArrayList<>();
        for (int i = 1; i <= customers; i++) emails.add("load-user-" + i + "@example.com");
        Map<String, User> byEmail = new HashMap<>();
        for (User u : userRepo.findAllByEmailIn(emails)) byEmail.put(u.getEmail(), u);

        String sharedHash = encoder.encode("password123");
        List<User> toCreate = new ArrayList<>();
        for (int i = 1; i <= customers; i++) {
            String email = emails.get(i - 1);
            if (byEmail.containsKey(email)) continue;
            User u = new User();
            u.setName("Load User " + i);
            u.setEmail(email);
            u.setPasswordHash(sharedHash);
            u.setRole(Role.CUSTOMER);
            toCreate.add(u);
        }
        for (User u : userRepo.saveAll(toCreate)) byEmail.put(u.getEmail(), u);

        List<String> tokens = new ArrayList<>();
        for (String email : emails) {
            User u = byEmail.get(email);
            tokens.add(jwt.generate(u.getId(), u.getEmail(), u.getRole()));
        }

        List<Long> eventIds = new ArrayList<>();
        for (int i = 1; i <= events; i++) {
            Event e = new Event();
            e.setOrganizerId(organizer.getId());
            e.setTitle("Load Test Event " + i);
            e.setDescription("Seeded for stress testing");
            e.setVenue("Test Arena");
            e.setStartTime(Instant.now().plus(30, ChronoUnit.DAYS));
            e.setTotalSeats(seats);
            e.setAvailableSeats(seats);
            e.setPrice(new BigDecimal("10.00"));
            eventIds.add(eventRepo.save(e).getId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organizerId", organizer.getId());
        out.put("organizerToken", jwt.generate(organizer.getId(), organizer.getEmail(), Role.ORGANIZER));
        out.put("seatsPerEvent", seats);
        out.put("eventIds", eventIds);
        out.put("tokens", tokens);
        return out;
    }
}
