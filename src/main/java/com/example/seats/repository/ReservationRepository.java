package com.example.seats.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.seats.model.Reservation;

@Repository
public class ReservationRepository {

	private final JdbcTemplate jdbc;

	public ReservationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(Reservation r) {
		jdbc.update("""
				INSERT INTO reservations
				    (id, show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash)
				VALUES (?, ?, ?, ?, ?, ?::reservation_status, ?, ?)
				""", r.id(), r.showId(), r.userId(), r.seats().toArray(String[]::new), r.amountPaise(),
				r.status().json(), r.idempotencyKey(), r.requestHash());
	}
}
