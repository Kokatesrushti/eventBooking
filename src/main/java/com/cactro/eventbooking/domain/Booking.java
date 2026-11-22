package com.cactro.eventbooking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "bookings")
@Getter @Setter @NoArgsConstructor
public class Booking {
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id")
    private Long eventId;
    @Column(name = "customer_id")
    private Long customerId;
    private int quantity;
    private String status = CONFIRMED;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "email_sent")
    private boolean emailSent;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
