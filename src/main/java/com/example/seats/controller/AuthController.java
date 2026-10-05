package com.example.seats.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.seats.model.TokenRequest;
import com.example.seats.model.TokenResponse;
import com.example.seats.service.TokenService;

import jakarta.validation.Valid;

@RestController
public class AuthController {

	private final TokenService tokenService;

	public AuthController(TokenService tokenService) {
		this.tokenService = tokenService;
	}

	@PostMapping("/auth/token")
	public TokenResponse token(@Valid @RequestBody TokenRequest req) {
		return tokenService.issue(req.userId());
	}
}
