package com.docqa.controller;

import com.docqa.dto.response.DocumentResponse;
import com.docqa.exception.DocumentNotFoundException;
import com.docqa.model.User;
import com.docqa.security.JwtTokenProvider;
import com.docqa.security.UserDetailsServiceImpl;
import com.docqa.service.DocumentService;
import com.docqa.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
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

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DocumentService documentService;
    @MockBean UserService userService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsServiceImpl;

    private static final String BASE = "/api/v1/documents";
    private static final String TEST_EMAIL = "user@example.com";

    private User stubUser(UUID userId) {
        User u = User.builder().email(TEST_EMAIL).passwordHash("h").build();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userService.getByEmail(TEST_EMAIL)).thenReturn(u);
        return u;
    }

    private DocumentResponse sampleResponse(UUID docId) {
        return new DocumentResponse(docId, "My Doc", "test.pdf",
                "application/pdf", 1024L, "UPLOADED", 0,
                Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void upload_validFile_returns201() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        stubUser(userId);
        when(documentService.upload(any(), eq("My Doc"), eq(userId)))
                .thenReturn(sampleResponse(docId));

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "pdf-content".getBytes());

        mockMvc.perform(multipart(BASE)
                        .file(file)
                        .part(new MockPart("title", "My Doc".getBytes())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("My Doc"));
    }

    @Test
    void upload_unauthenticated_returns4xx() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "pdf".getBytes());

        mockMvc.perform(multipart(BASE)
                        .file(file)
                        .part(new MockPart("title", "My Doc".getBytes())))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void list_returns200WithPage() throws Exception {
        UUID userId = UUID.randomUUID();
        stubUser(userId);
        when(documentService.listForUser(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse(UUID.randomUUID()))));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void list_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void getById_found_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        stubUser(userId);
        when(documentService.getForUser(docId, userId)).thenReturn(sampleResponse(docId));

        mockMvc.perform(get(BASE + "/{id}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(docId.toString()));
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void getById_notFound_returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        stubUser(userId);
        when(documentService.getForUser(docId, userId))
                .thenThrow(new DocumentNotFoundException(docId));

        mockMvc.perform(get(BASE + "/{id}", docId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void delete_existing_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        stubUser(userId);
        doNothing().when(documentService).delete(docId, userId);

        mockMvc.perform(delete(BASE + "/{id}", docId))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(TEST_EMAIL)
    void delete_notFound_returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        stubUser(userId);
        doThrow(new DocumentNotFoundException(docId))
                .when(documentService).delete(docId, userId);

        mockMvc.perform(delete(BASE + "/{id}", docId))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_unauthenticated_returns4xx() throws Exception {
        mockMvc.perform(delete(BASE + "/{id}", UUID.randomUUID()))
                .andExpect(status().is4xxClientError());
    }
}
