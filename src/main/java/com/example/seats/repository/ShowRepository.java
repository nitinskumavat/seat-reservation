package com.example.seats.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.seats.model.Show;

@Repository
public class ShowRepository {

	private final JdbcTemplate jdbc;

	public ShowRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(Show show) {
		jdbc.update("""
				INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
				VALUES (?, ?, ?, ?, ?)
				""", show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats());
	}

	public Optional<Show> findById(UUID id) {
		return jdbc.query("""
				SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?
				""", (rs, i) -> new Show(rs.getObject("id", UUID.class), rs.getString("name"),
				rs.getLong("price_paise"), rs.getInt("per_user_limit"), rs.getInt("total_seats")), id)
				.stream().findFirst();
	}
}
