package com.example.seats.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import com.example.seats.model.Seat;
import com.example.seats.model.SeatStatus;

@Repository
public class SeatRepository {

	private final JdbcTemplate jdbc;

	public SeatRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** One statement for all seats, so large halls don't cost one round trip per seat. */
	public void insertAll(UUID showId, List<String> labels) {
		jdbc.update("INSERT INTO seats (show_id, label) SELECT ?, unnest(?::text[])", ps -> {
			ps.setObject(1, showId);
			ps.setArray(2, ps.getConnection().createArrayOf("text", labels.toArray()));
		});
	}

	/**
	 * Row-locks the requested seats in label order. Every transaction locks in the same order,
	 * so two multi-seat requests can never each hold a seat the other is waiting for. A waiter
	 * re-reads the row once the lock is released, so it sees the winner's committed status.
	 */
	public List<Seat> lockForUpdate(UUID showId, List<String> labels) {
		return jdbc.query("""
				SELECT label, status FROM seats
				WHERE show_id = ? AND label = ANY(?)
				ORDER BY label
				FOR UPDATE
				""", (rs, i) -> new Seat(rs.getString("label"), SeatStatus.fromDb(rs.getString("status"))),
				showId, labels.toArray(String[]::new));
	}

	/** Only called while holding the row locks taken by {@link #lockForUpdate}. */
	public void confirm(UUID showId, List<String> labels, UUID reservationId, String userId) {
		jdbc.update("""
				UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?
				WHERE show_id = ? AND label = ANY(?)
				""", reservationId, userId, showId, labels.toArray(String[]::new));
	}

	/**
	 * Returns seats to available only while they still belong to this reservation, so releasing
	 * an old reservation can never free a seat that is now someone else's. Callers lock the rows
	 * first with {@link #lockForUpdate} to keep the label lock order.
	 */
	public void release(UUID showId, List<String> labels, UUID reservationId) {
		jdbc.update("""
				UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL
				WHERE show_id = ? AND label = ANY(?) AND reservation_id = ?
				""", showId, labels.toArray(String[]::new), reservationId);
	}

	/** Seat counts for every show in one query, keyed by {@link #countKey}. */
	public Map<String, Integer> countAllByShowAndStatus() {
		Map<String, Integer> counts = new HashMap<>();
		jdbc.query("SELECT show_id, status, count(*) AS n FROM seats GROUP BY show_id, status",
				(RowCallbackHandler) rs -> counts.put(countKey(rs.getObject("show_id", UUID.class),
						SeatStatus.fromDb(rs.getString("status"))), rs.getInt("n")));
		return counts;
	}

	public static String countKey(UUID showId, SeatStatus status) {
		return showId + "|" + status.json();
	}

	/** A single SELECT is one snapshot, so the statuses it returns are mutually consistent. */
	public List<Seat> findByShow(UUID showId) {
		return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY label",
				(rs, i) -> new Seat(rs.getString("label"), SeatStatus.fromDb(rs.getString("status"))), showId);
	}
}
