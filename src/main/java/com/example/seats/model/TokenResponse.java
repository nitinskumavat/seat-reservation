package com.example.seats.model;

import java.time.Instant;

public record TokenResponse(String token, String userId, Instant expiresAt) {
}
