package com.example.seats.exception;

import org.springframework.http.HttpStatus;

public class SeatTakenException extends ApiException {

	public SeatTakenException() {
		super(HttpStatus.CONFLICT, "seat_taken", "one or more requested seats are not available");
	}
}
