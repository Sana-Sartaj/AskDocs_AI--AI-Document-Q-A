package com.docqa.controller;

import com.docqa.dto.request.AuthRequest;
import com.docqa.dto.request.RegisterRequest;
import com.docqa.dto.response.AuthResponse;
import com.docqa.exception.UserAlreadyExistsException;
import com.docqa.security.JwtTokenProvider;
import com.docqa.security.UserDetailsServiceImpl;
import com.docqa.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AuthService authService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsServiceImpl;

    private static final String BASE = "/api/v1/auth";

    @Test
    void register_validRequest_returns201() throws Exception {
        AuthResponse resp = new AuthResponse("tok", 3600000L,
                UUID.randomUUID(), "alice@example.com", "Alice", "USER");
        when(authService.register(any())).thenReturn(resp);

        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("alice@example.com", "password123", "Alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("tok"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        when(authService.register(any())).thenThrow(new UserAlreadyExistsException("alice@example.com"));

        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("alice@example.com", "password123", "Alice"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("not-an-email", "password123", "Alice"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_passwordTooShort_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("alice@example.com", "short", "Alice"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_singleCharName_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("alice@example.com", "password123", "A"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_validCredentials_returns200() throws Exception {
        AuthResponse resp = new AuthResponse("tok", 3600000L,
                UUID.randomUUID(), "alice@example.com", "Alice", "USER");
        when(authService.login(any())).thenReturn(resp);

        mockMvc.perform(post(BASE + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthRequest("alice@example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("tok"));
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post(BASE + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthRequest("alice@example.com", "wrongpass1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void login_invalidEmailFormat_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthRequest("not-an-email", "password123"))))
                .andExpect(status().isBadRequest());
    }
}
