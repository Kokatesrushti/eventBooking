package com.cactro.eventbooking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "jobs")
@Getter @Setter @NoArgsConstructor
public class Job {
    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String type;
    private String payload;
    private String status = PENDING;
    private int attempts;
    @Column(name = "run_at")
    private Instant runAt = Instant.now();
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "locked_at")
    private Instant lockedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
