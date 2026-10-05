package com.example.seats.model;

import java.util.List;
import java.util.UUID;

public record ReservationResponse(
		UUID reservationId,
		UUID showId,
		String userId,
		List<String> seats,
		long amountPaise,
		ReservationStatus status) {

	public static ReservationResponse of(Reservation r) {
		return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(), r.status());
	}
}
