package com.example.seats.exception;

import org.springframework.http.HttpStatus;

public class KeyReusedException extends ApiException {

	public KeyReusedException() {
		super(HttpStatus.CONFLICT, "key_reused", "idempotency key was already used with a different request");
	}
}
