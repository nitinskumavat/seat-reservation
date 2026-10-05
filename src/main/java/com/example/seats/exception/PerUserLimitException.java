package com.example.seats.exception;

import org.springframework.http.HttpStatus;

public class PerUserLimitException extends ApiException {

	public PerUserLimitException(int limit) {
		super(HttpStatus.CONFLICT, "per_user_limit", "a user may hold at most " + limit + " seats for this show");
	}
}
