package com.cactro.eventbooking.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public record EventRequest(
        @NotBlank @Size(max = 200) String title,
        String description,
        @NotBlank @Size(max = 200) String venue,
        @NotNull @Future Instant startTime,
        @Min(1) int totalSeats,
        @NotNull @DecimalMin("0.0") BigDecimal price) {}
