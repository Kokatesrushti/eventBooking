package com.cactro.eventbooking.service;

import com.cactro.eventbooking.domain.Booking;
import com.cactro.eventbooking.domain.Event;
import com.cactro.eventbooking.domain.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Map;

/**
 * Sends real email through the Resend HTTP API (SMTP ports are blocked on most free hosts).
 * provider=simulated sleeps for a configurable latency and is ONLY for load tests, because real
 * providers rate-limit (Resend free tier ~2 req/s) and would hide the app's real limits.
 */
@Service
@Slf4j
public class EmailService {
    private final String provider;
    private final String apiKey;
    private final String from;
    private final long simulatedLatencyMs;
    private final RestClient http;

    public EmailService(@Value("${app.mail.provider}") String provider,
                        @Value("${app.mail.resend-api-key}") String apiKey,
                        @Value("${app.mail.from}") String from,
                        @Value("${app.mail.simulated-latency-ms}") long simulatedLatencyMs) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.from = from;
        this.simulatedLatencyMs = simulatedLatencyMs;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        this.http = RestClient.builder().baseUrl("https://api.resend.com").requestFactory(f).build();
    }

    public void sendBookingConfirmation(Booking b, Event e, User u) {
        String html = "<h2>Booking confirmed \u2705</h2>"
                + "<p>Hi " + esc(u.getName()) + ", your booking is confirmed.</p>"
                + "<ul><li>Booking #" + b.getId() + "</li>"
                + "<li>Event: " + esc(e.getTitle()) + "</li>"
                + "<li>Venue: " + esc(e.getVenue()) + "</li>"
                + "<li>Starts: " + e.getStartTime() + "</li>"
                + "<li>Tickets: " + b.getQuantity() + "</li></ul>";
        send(u.getEmail(), "Booking confirmed: " + e.getTitle(), html);
    }

    public void sendEventUpdate(Event e, User u, String summary) {
        String html = "<h2>Event update</h2>"
                + "<p>Hi " + esc(u.getName()) + ", an event you booked has changed.</p>"
                + "<p><b>" + esc(e.getTitle()) + "</b> (" + esc(e.getStatus()) + ")</p>"
                + "<p>" + esc(summary) + "</p>"
                + "<ul><li>Venue: " + esc(e.getVenue()) + "</li><li>Starts: " + e.getStartTime() + "</li></ul>";
        send(u.getEmail(), "Update: " + e.getTitle(), html);
    }

    public void send(String to, String subject, String html) {
        if ("resend".equalsIgnoreCase(provider)) {
            if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("RESEND_API_KEY not set");
            http.post().uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("from", from, "to", List.of(to), "subject", subject, "html", html))
                    .retrieve()
                    .toBodilessEntity();     // non-2xx => exception => job retried with backoff
            log.info("Email sent via Resend to {}", to);
        } else {
            try { Thread.sleep(simulatedLatencyMs); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            log.debug("[SIMULATED EMAIL] to={} subject={}", to, subject);
        }
    }

    private static String esc(String s) { return HtmlUtils.htmlEscape(s == null ? "" : s); }
}
