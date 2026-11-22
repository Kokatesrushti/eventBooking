package com.cactro.eventbooking.security;

import com.cactro.eventbooking.domain.Role;

/** Principal built purely from JWT claims: no DB lookup on the request path. */
public record AuthUser(Long id, String email, Role role) {}
