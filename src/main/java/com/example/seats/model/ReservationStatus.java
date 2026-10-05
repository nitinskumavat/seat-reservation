package com.example.seats.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ReservationStatus {
	CONFIRMED, CANCELLED;

	public static ReservationStatus fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}
}
