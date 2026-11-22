package com.cactro.eventbooking.exception;

public class SoldOutException extends RuntimeException {
    public SoldOutException() { super("Not enough seats available"); }
}
