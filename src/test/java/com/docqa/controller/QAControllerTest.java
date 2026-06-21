package com.docqa.controller;

import com.docqa.dto.request.QuestionRequest;
import com.docqa.dto.response.QuestionResponse;
import com.docqa.model.User;
import com.docqa.security.JwtTokenProvider;
import com.docqa.security.UserDetailsServiceImpl;
import com.docqa.service.QAService;
import com.docqa.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QAController.class)
class QAControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean QAService qaService;
    @MockBean UserService userService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsServiceImpl;

    private static final String BASE = "/api/v1/qa";
    private static final String TEST_EMAIL = "user@example.com";

    private User stubUser(UUID userId) {
        User u = User.builder().email(TEST_EMAIL).passwordHash("h").build();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userService.getByEmail(TEST_EMAIL)).thenReturn(u);
        return u;
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void ask_validQuestion_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        stubUser(userId);
        UUID sessionId = UUID.randomUUID();
        QuestionResponse resp = new QuestionResponse(
                sessionId, UUID.randomUUID(), "The answer",
                List.of(), 0.8, Instant.now());
        when(qaService.ask(any(), eq(userId))).thenReturn(resp);

        mockMvc.perform(post(BASE + "/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QuestionRequest("What is this?", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("The answer"))
                .andExpect(jsonPath("$.data.confidence").value(0.8));
    }

    @Test
    void ask_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(post(BASE + "/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QuestionRequest("What is this?", null, null))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void ask_blankQuestion_returns400() throws Exception {
        stubUser(UUID.randomUUID());

        mockMvc.perform(post(BASE + "/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
