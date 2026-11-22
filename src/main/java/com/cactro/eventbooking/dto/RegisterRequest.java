package com.cactro.eventbooking.dto;

import com.cactro.eventbooking.domain.Role;
import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6, max = 72) String password,
        @NotNull Role role) {}
