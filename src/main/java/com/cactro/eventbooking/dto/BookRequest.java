package com.cactro.eventbooking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record BookRequest(@Min(1) @Max(10) int quantity) {}
