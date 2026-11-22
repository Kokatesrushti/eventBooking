package com.cactro.eventbooking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "events")
@Getter @Setter @NoArgsConstructor
public class Event {
    public static final String ACTIVE = "ACTIVE";
    public static final String CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "organizer_id")
    private Long organizerId;
    private String title;
    private String description;
    private String venue;
    @Column(name = "start_time")
    private Instant startTime;
    @Column(name = "total_seats")
    private int totalSeats;
    @Column(name = "available_seats")
    private int availableSeats;
    private BigDecimal price;
    private String status = ACTIVE;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
