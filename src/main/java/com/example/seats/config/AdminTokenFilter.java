package com.example.seats.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates admin calls via a static X-Admin-Token header. Kept separate from
 * Authorization: Bearer, which is reserved for user JWTs.
 */
class AdminTokenFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Admin-Token";

	private final byte[] adminToken;

	AdminTokenFilter(String adminToken) {
		this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String supplied = request.getHeader(HEADER);
		if (supplied != null && MessageDigest.isEqual(adminToken, supplied.getBytes(StandardCharsets.UTF_8))) {
			SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
					"admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		}
		chain.doFilter(request, response);
	}
}
