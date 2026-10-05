package com.example.seats.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.seats.exception.BadRequestException;
import com.example.seats.model.ReservationResponse;
import com.example.seats.model.ReserveRequest;
import com.example.seats.service.ReservationService;

import jakarta.validation.Valid;

@RestController
public class ReservationController {

	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	/** The acting user is always the token's subject, never anything in the body. */
	@PostMapping("/shows/{showId}/reserve")
	@ResponseStatus(HttpStatus.CREATED)
	public ReservationResponse reserve(@PathVariable UUID showId,
			@RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
			@Valid @RequestBody ReserveRequest req, @AuthenticationPrincipal Jwt jwt) {
		return reservationService.reserve(jwt.getSubject(), showId, req.seats(), idempotencyKey(headerKey, req));
	}

	private static String idempotencyKey(String headerKey, ReserveRequest req) {
		String bodyKey = req.idempotencyKey();
		if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
			throw new BadRequestException("Idempotency-Key header and idempotency_key body field differ");
		}
		String key = headerKey != null ? headerKey : bodyKey;
		if (key == null || key.isBlank() || key.length() > 128) {
			throw new BadRequestException("idempotency key is required (1-128 characters)");
		}
		return key;
	}
}
