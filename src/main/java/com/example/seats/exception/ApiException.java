package com.example.seats.exception;

import org.springframework.http.HttpStatus;

/** A domain outcome that maps to a 4xx response with a machine-readable reason. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String reason;

	public ApiException(HttpStatus status, String reason, String message) {
		super(message);
		this.status = status;
		this.reason = reason;
	}

	public HttpStatus status() {
		return status;
	}

	public String reason() {
		return reason;
	}
}
