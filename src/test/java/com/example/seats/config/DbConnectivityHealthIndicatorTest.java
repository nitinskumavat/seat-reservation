package com.example.seats.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class DbConnectivityHealthIndicatorTest {

	@Test
	void unreachableDatabaseIsDownWithinSeconds() {
		var indicator = new DbConnectivityHealthIndicator("jdbc:postgresql://localhost:1/none", "u", "p");
		long start = System.nanoTime();
		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5000);
	}
}
