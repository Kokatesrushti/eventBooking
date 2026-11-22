package com.cactro.eventbooking.web;

import com.cactro.eventbooking.dto.AuthResponse;
import com.cactro.eventbooking.dto.LoginRequest;
import com.cactro.eventbooking.dto.RegisterRequest;
import com.cactro.eventbooking.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest r) { return authService.register(r); }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) { return authService.login(r); }
}
