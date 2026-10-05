package com.example.seats.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${app.admin-token}") String adminToken)
			throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.addFilterBefore(new AdminTokenFilter(adminToken), BasicAuthenticationFilter.class)
			.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/error").permitAll()
				.requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
				.requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
				.requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
				.requestMatchers(HttpMethod.POST, "/shows/*/reserve", "/reservations/*/cancel").authenticated()
				.anyRequest().denyAll());
		return http.build();
	}

}
