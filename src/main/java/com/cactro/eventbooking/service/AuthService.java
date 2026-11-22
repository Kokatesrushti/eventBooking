package com.cactro.eventbooking.service;

import com.cactro.eventbooking.domain.User;
import com.cactro.eventbooking.dto.AuthResponse;
import com.cactro.eventbooking.dto.LoginRequest;
import com.cactro.eventbooking.dto.RegisterRequest;
import com.cactro.eventbooking.exception.ConflictException;
import com.cactro.eventbooking.repo.UserRepository;
import com.cactro.eventbooking.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthResponse register(RegisterRequest r) {
        String email = r.email().trim().toLowerCase();
        if (userRepo.existsByEmail(email)) throw new ConflictException("Email already registered");
        User u = new User();
        u.setName(r.name());
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(r.password()));
        u.setRole(r.role());
        u = userRepo.save(u);
        return new AuthResponse(jwt.generate(u.getId(), u.getEmail(), u.getRole()), u.getId(), u.getRole());
    }

    public AuthResponse login(LoginRequest r) {
        User u = userRepo.findByEmail(r.email().trim().toLowerCase())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!encoder.matches(r.password(), u.getPasswordHash()))
            throw new BadCredentialsException("Invalid email or password");
        return new AuthResponse(jwt.generate(u.getId(), u.getEmail(), u.getRole()), u.getId(), u.getRole());
    }
}
