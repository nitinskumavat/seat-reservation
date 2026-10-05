package com.example.seats.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Readiness check that opens its own short-timeout connection instead of borrowing from the
 * pool. It answers within about two seconds when Postgres is down, and it is not stuck behind
 * a busy pool during a burst, which would wrongly report the service as not ready.
 */
@Component
class DbConnectivityHealthIndicator extends AbstractHealthIndicator {

	private final String url;
	private final Properties props = new Properties();

	/** Uses the same connection details as the pool, whether they come from properties or a test container. */
	@Autowired
	DbConnectivityHealthIndicator(JdbcConnectionDetails db) {
		this(db.getJdbcUrl(), db.getUsername(), db.getPassword());
	}

	DbConnectivityHealthIndicator(String url, String username, String password) {
		this.url = url;
		props.setProperty("user", username);
		props.setProperty("password", password);
		props.setProperty("connectTimeout", "2");
		props.setProperty("socketTimeout", "2");
		props.setProperty("loginTimeout", "2");
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) throws Exception {
		try (Connection c = DriverManager.getConnection(url, props); Statement s = c.createStatement()) {
			s.execute("SELECT 1");
		}
		builder.up();
	}
}
