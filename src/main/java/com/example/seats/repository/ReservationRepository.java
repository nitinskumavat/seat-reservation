package com.example.seats.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.seats.model.Reservation;
import com.example.seats.model.ReservationStatus;

@Repository
public class ReservationRepository {

	private static final String COLUMNS =
			"id, show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash";

	private final JdbcTemplate jdbc;

	public ReservationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Claims the (user, idempotency key) pair. Returns false if it is already taken. If another
	 * transaction holds an uncommitted row with the same key, this blocks until that transaction
	 * ends: it commits (false here) or rolls back (we insert).
	 */
	public boolean insertIfAbsent(Reservation r) {
		return jdbc.update("INSERT INTO reservations (" + COLUMNS + """
				)
				VALUES (?, ?, ?, ?, ?, ?::reservation_status, ?, ?)
				ON CONFLICT (user_id, idempotency_key) DO NOTHING
				""", r.id(), r.showId(), r.userId(), r.seats().toArray(String[]::new), r.amountPaise(),
				r.status().json(), r.idempotencyKey(), r.requestHash()) == 1;
	}

	public Optional<Reservation> findByUserAndKey(String userId, String key) {
		return jdbc.query("SELECT " + COLUMNS + " FROM reservations WHERE user_id = ? AND idempotency_key = ?",
				ReservationRepository::map, userId, key).stream().findFirst();
	}

	/**
	 * Row-locks the reservation if it belongs to the user. Someone else's reservation looks the
	 * same as a missing one.
	 */
	public Optional<Reservation> lockOwned(UUID id, String userId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM reservations WHERE id = ? AND user_id = ? FOR UPDATE",
				ReservationRepository::map, id, userId).stream().findFirst();
	}

	public void markCancelled(UUID id) {
		jdbc.update("UPDATE reservations SET status = 'cancelled' WHERE id = ?", id);
	}

	private static Reservation map(ResultSet rs, int rowNum) throws SQLException {
		return new Reservation(rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class),
				rs.getString("user_id"), List.of((String[]) rs.getArray("seats").getArray()),
				rs.getLong("amount_paise"), ReservationStatus.fromDb(rs.getString("status")),
				rs.getString("idempotency_key"), rs.getString("request_hash"));
	}
}
