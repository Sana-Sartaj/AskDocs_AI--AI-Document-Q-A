package com.docqa.controller;

import com.docqa.model.User;
import com.docqa.security.JwtTokenProvider;
import com.docqa.security.UserDetailsServiceImpl;
import com.docqa.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean UserService userService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsServiceImpl;

    private static final String TEST_EMAIL = "user@example.com";

    @Test
    @WithMockUser(TEST_EMAIL)
    void me_authenticatedUser_returnsProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .email(TEST_EMAIL)
                .passwordHash("h")
                .fullName("Test User")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userService.getByEmail(TEST_EMAIL)).thenReturn(user);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.data.fullName").value("Test User"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void me_userWithNullFullName_returnsEmptyString() throws Exception {
        User user = User.builder().email(TEST_EMAIL).passwordHash("h").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userService.getByEmail(TEST_EMAIL)).thenReturn(user);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value(""));
    }

    @Test
    void me_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().is4xxClientError());
    }
}
