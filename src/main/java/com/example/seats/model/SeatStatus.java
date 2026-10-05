package com.example.seats.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SeatStatus {
	AVAILABLE, HELD, CONFIRMED;

	public static SeatStatus fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}
}
