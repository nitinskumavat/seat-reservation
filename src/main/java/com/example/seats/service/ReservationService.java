package com.example.seats.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seats.exception.BadRequestException;
import com.example.seats.exception.KeyReusedException;
import com.example.seats.exception.NotFoundException;
import com.example.seats.exception.PerUserLimitException;
import com.example.seats.exception.SeatTakenException;
import com.example.seats.model.Reservation;
import com.example.seats.model.ReservationResponse;
import com.example.seats.model.ReservationStatus;
import com.example.seats.model.Seat;
import com.example.seats.model.SeatStatus;
import com.example.seats.model.Show;
import com.example.seats.repository.ReservationRepository;
import com.example.seats.repository.SeatRepository;
import com.example.seats.repository.ShowRepository;
import com.example.seats.repository.UserRepository;
import com.example.seats.repository.UserShowCountRepository;

@Service
public class ReservationService {

	private final ShowRepository shows;
	private final SeatRepository seats;
	private final ReservationRepository reservations;
	private final UserRepository users;
	private final UserShowCountRepository userShowCounts;

	public ReservationService(ShowRepository shows, SeatRepository seats, ReservationRepository reservations,
			UserRepository users, UserShowCountRepository userShowCounts) {
		this.shows = shows;
		this.seats = seats;
		this.reservations = reservations;
		this.users = users;
		this.userShowCounts = userShowCounts;
	}

	/**
	 * All-or-nothing: either every requested seat is confirmed to this user, or the transaction
	 * rolls back and nothing changes. Locks are always taken in the same order: idempotency key,
	 * then the user's counter for the show, then seats by label.
	 */
	@Transactional
	public ReservationResponse reserve(String userId, UUID showId, List<String> requestedSeats, String key) {
		List<String> labels = requestedSeats.stream().sorted().toList();
		if (new HashSet<>(labels).size() != labels.size()) {
			throw new BadRequestException("duplicate seat labels");
		}
		Show show = shows.findById(showId).orElseThrow(() -> new NotFoundException("show not found"));

		// Tokens outlive a database reset, so make sure the user row exists for the foreign keys.
		users.upsert(userId);

		Reservation reservation = new Reservation(UUID.randomUUID(), showId, userId, labels,
				Math.multiplyExact(show.pricePaise(), labels.size()), ReservationStatus.CONFIRMED, key,
				requestHash(showId, labels));
		if (!reservations.insertIfAbsent(reservation)) {
			// The key already belongs to a committed reservation: replay it, or reject a changed request.
			Reservation existing = reservations.findByUserAndKey(userId, key)
				.orElseThrow(() -> new IllegalStateException("conflicting reservation not visible"));
			if (!existing.requestHash().equals(reservation.requestHash())) {
				throw new KeyReusedException();
			}
			return ReservationResponse.of(existing);
		}

		if (!userShowCounts.tryAdd(userId, showId, labels.size(), show.perUserLimit())) {
			throw new PerUserLimitException(show.perUserLimit());
		}

		List<Seat> locked = seats.lockForUpdate(showId, labels);
		if (locked.size() != labels.size()) {
			throw new NotFoundException("seat not found");
		}
		if (locked.stream().anyMatch(s -> s.status() != SeatStatus.AVAILABLE)) {
			throw new SeatTakenException();
		}
		seats.confirm(showId, labels, reservation.id(), userId);
		return ReservationResponse.of(reservation);
	}

	/**
	 * Owner-only and idempotent: cancelling twice returns the cancelled reservation. Lock order
	 * is reservation, then the user's counter, then seats by label.
	 */
	@Transactional
	public ReservationResponse cancel(String userId, UUID reservationId) {
		Reservation r = reservations.lockOwned(reservationId, userId)
			.orElseThrow(() -> new NotFoundException("reservation not found"));
		if (r.status() == ReservationStatus.CANCELLED) {
			return ReservationResponse.of(r);
		}
		userShowCounts.subtract(userId, r.showId(), r.seats().size());
		seats.lockForUpdate(r.showId(), r.seats());
		seats.release(r.showId(), r.seats(), r.id());
		reservations.markCancelled(r.id());
		return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(),
				ReservationStatus.CANCELLED);
	}

	/** Seats are sorted first, so the same seats in a different order hash the same. */
	static String requestHash(UUID showId, List<String> sortedLabels) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] digest = sha.digest((showId + "|" + String.join(",", sortedLabels)).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
