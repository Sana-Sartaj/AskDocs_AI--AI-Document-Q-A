package com.docqa.controller;

import com.docqa.dto.request.QuestionRequest;
import com.docqa.dto.response.ChatSessionSummary;
import com.docqa.dto.response.ConversationResponse;
import com.docqa.dto.response.QuestionResponse;
import com.docqa.exception.ApiException;
import com.docqa.model.User;
import com.docqa.security.JwtTokenProvider;
import com.docqa.security.UserDetailsServiceImpl;
import com.docqa.service.ChatService;
import com.docqa.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ChatService chatService;
    @MockBean UserService userService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsServiceImpl;

    private static final String BASE = "/api/v1/chat";
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
                sessionId, UUID.randomUUID(), "Chat answer", List.of(), 0.7, Instant.now());
        when(chatService.ask(any(), eq(userId))).thenReturn(resp);

        mockMvc.perform(post(BASE + "/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QuestionRequest("Tell me more", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("Chat answer"));
    }

    @Test
    void ask_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(post(BASE + "/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QuestionRequest("Tell me more", null, null))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void listSessions_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        stubUser(userId);
        ChatSessionSummary summary = new ChatSessionSummary(
                UUID.randomUUID(), "Session 1", 3L, Instant.now(), Instant.now());
        when(chatService.listSessions(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary)));

        mockMvc.perform(get(BASE + "/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("Session 1"));
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void getSession_found_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubUser(userId);
        ConversationResponse resp = new ConversationResponse(
                sessionId, "My Session", List.of(), Instant.now(), Instant.now());
        when(chatService.getSession(sessionId, userId)).thenReturn(resp);

        mockMvc.perform(get(BASE + "/sessions/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("My Session"));
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void getSession_notFound_returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubUser(userId);
        when(chatService.getSession(sessionId, userId))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Conversation not found: " + sessionId));

        mockMvc.perform(get(BASE + "/sessions/{id}", sessionId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void deleteSession_existing_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubUser(userId);
        doNothing().when(chatService).deleteSession(sessionId, userId);

        mockMvc.perform(delete(BASE + "/sessions/{id}", sessionId))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void deleteSession_notFound_returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubUser(userId);
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "Conversation not found: " + sessionId))
                .when(chatService).deleteSession(sessionId, userId);

        mockMvc.perform(delete(BASE + "/sessions/{id}", sessionId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSessions_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get(BASE + "/sessions")).andExpect(status().is4xxClientError());
    }
}
