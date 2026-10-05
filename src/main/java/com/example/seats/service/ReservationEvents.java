package com.example.seats.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.seats.model.Reservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * One place that logs and counts reservation outcomes. Successes are recorded only after the
 * transaction commits, so the counters never include work that was rolled back.
 */
@Component
public class ReservationEvents {

	private static final Logger log = LoggerFactory.getLogger(ReservationEvents.class);

	private final MeterRegistry registry;
	private final Counter confirmed;
	private final Counter cancelled;

	public ReservationEvents(MeterRegistry registry) {
		this.registry = registry;
		this.confirmed = Counter.builder("reservations.confirmed").register(registry);
		this.cancelled = Counter.builder("reservations.cancelled").register(registry);
		// Create the main decline series at 0 up front: a series that first appears already at
		// N looks like no change to Prometheus' increase(), hiding the first burst.
		for (String reason : new String[] { "seat_taken", "per_user_limit", "key_reused", "idempotent_replay" }) {
			declinedCounter(reason);
		}
	}

	void confirmed(Reservation r) {
		afterCommit(() -> {
			confirmed.increment();
			log(r.userId(), r.showId(), r.seats(), "confirmed", r.id());
		});
	}

	void replayed(Reservation r) {
		afterCommit(() -> {
			declinedCounter("idempotent_replay").increment();
			log(r.userId(), r.showId(), r.seats(), "idempotent_replay", r.id());
		});
	}

	void declined(String reason, String userId, UUID showId, List<String> seats) {
		declinedCounter(reason).increment();
		log(userId, showId, seats, reason, null);
	}

	void cancelled(Reservation r) {
		afterCommit(() -> {
			cancelled.increment();
			log(r.userId(), r.showId(), r.seats(), "cancelled", r.id());
		});
	}

	private Counter declinedCounter(String reason) {
		return registry.counter("reservations.declined", "reason", reason);
	}

	private static void log(String userId, UUID showId, List<String> seats, String outcome, UUID reservationId) {
		log.atInfo()
			.addKeyValue("user_id", userId)
			.addKeyValue("show_id", showId)
			.addKeyValue("seats", seats)
			.addKeyValue("outcome", outcome)
			.addKeyValue("reservation_id", reservationId)
			.log("reservation {}", outcome);
	}

	private static void afterCommit(Runnable action) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}
}
