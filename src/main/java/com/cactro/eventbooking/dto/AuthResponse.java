package com.cactro.eventbooking.dto;

import com.cactro.eventbooking.domain.Role;

public record AuthResponse(String token, Long userId, Role role) {}
