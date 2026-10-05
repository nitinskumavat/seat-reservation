package com.example.seats.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tags every log line of a request with request_id (the caller's X-Request-ID if it is safe to
 * log, otherwise a new one), echoes it back, and writes one access line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Request-ID";

	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final Logger log = LoggerFactory.getLogger("access");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String supplied = request.getHeader(HEADER);
		String requestId = supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied
				: UUID.randomUUID().toString();
		long start = System.nanoTime();
		MDC.put("request_id", requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			log.atInfo()
				.addKeyValue("method", request.getMethod())
				.addKeyValue("path", request.getRequestURI())
				.addKeyValue("status", response.getStatus())
				.addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
				.log("{} {} {}", request.getMethod(), request.getRequestURI(), response.getStatus());
			MDC.remove("request_id");
		}
	}
}
