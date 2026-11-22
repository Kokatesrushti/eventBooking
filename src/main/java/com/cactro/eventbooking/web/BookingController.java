package com.cactro.eventbooking.web;

import com.cactro.eventbooking.dto.BookingResponse;
import com.cactro.eventbooking.security.AuthUser;
import com.cactro.eventbooking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class BookingController {
    private final BookingService bookingService;

    @GetMapping("/mine")
    public List<BookingResponse> mine(@AuthenticationPrincipal AuthUser u) { return bookingService.mine(u.id()); }

    @DeleteMapping("/{id}")
    public BookingResponse cancel(@AuthenticationPrincipal AuthUser u, @PathVariable Long id) {
        return bookingService.cancel(id, u.id());
    }
}
