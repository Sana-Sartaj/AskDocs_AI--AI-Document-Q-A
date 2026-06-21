package com.docqa.service;

import com.docqa.config.RagProperties;
import com.docqa.dto.request.QuestionRequest;
import com.docqa.dto.response.QuestionResponse;
import com.docqa.exception.ApiException;
import com.docqa.exception.DocumentNotFoundException;
import com.docqa.model.ChatMessage;
import com.docqa.observability.DocQaMetrics;
import com.docqa.model.ChatSession;
import com.docqa.model.Document;
import com.docqa.model.User;
import com.docqa.repository.ChatMessageRepository;
import com.docqa.repository.ChatSessionRepository;
import com.docqa.repository.DocumentRepository;
import com.docqa.repository.UserRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QAServiceTest {

    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private VectorRepository vectorRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private DocQaMetrics metrics;

    private QAService service;

    private static final UUID USER_ID     = UUID.randomUUID();
    private static final UUID DOC_ID      = UUID.randomUUID();
    private static final UUID SESSION_ID  = UUID.randomUUID();
    private static final String QUESTION  = "What is the document about?";
    private static final String ANSWER    = "The document is about testing.";

    private final RagProperties ragProperties = new RagProperties();
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @BeforeEach
    void setUp() {
        service = new QAService(
                chatLanguageModel, vectorRepository, documentRepository,
                sessionRepository, messageRepository, userRepository,
                promptBuilder, ragProperties, metrics);

        // Save always returns the message with an id and createdAt stamped
        given(messageRepository.save(any(ChatMessage.class))).willAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(m, "createdAt", Instant.now());
            return m;
        });

        // userRepository.getReferenceById used when creating a new session
        User user = User.builder().build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        given(userRepository.getReferenceById(USER_ID)).willReturn(user);

        given(chatLanguageModel.generate(anyList())).willReturn(Response.from(AiMessage.from(ANSWER)));
    }

    // ── scope selection ───────────────────────────────────────────────────────

    @Test
    void ask_noDocumentIds_usesUserScopedSearch() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        stubNewSession();
        given(vectorRepository.findSimilar(QUESTION, USER_ID)).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        service.ask(req, USER_ID);

        verify(vectorRepository).findSimilar(QUESTION, USER_ID);
        verify(vectorRepository, never()).findSimilarInDocuments(any(), any());
    }

    @Test
    void ask_withDocumentIds_usesDocumentScopedSearch() {
        QuestionRequest req = new QuestionRequest(QUESTION, List.of(DOC_ID), null);
        stubNewSession();
        Document doc = stubDocument(DOC_ID, "My PDF");
        given(documentRepository.findByIdInAndUserId(List.of(DOC_ID), USER_ID))
                .willReturn(List.of(doc));
        given(vectorRepository.findSimilarInDocuments(QUESTION, List.of(DOC_ID)))
                .willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        service.ask(req, USER_ID);

        verify(vectorRepository).findSimilarInDocuments(QUESTION, List.of(DOC_ID));
        verify(vectorRepository, never()).findSimilar(any(), any());
    }

    @Test
    void ask_documentNotOwnedByUser_throwsException() {
        QuestionRequest req = new QuestionRequest(QUESTION, List.of(DOC_ID), null);
        given(documentRepository.findByIdInAndUserId(List.of(DOC_ID), USER_ID))
                .willReturn(List.of()); // empty = not found / not owned

        assertThatThrownBy(() -> service.ask(req, USER_ID))
                .isInstanceOf(ApiException.class);
    }

    // ── session management ────────────────────────────────────────────────────

    @Test
    void ask_noConversationId_createsNewSessionTitledFromQuestion() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());
        given(sessionRepository.save(any(ChatSession.class))).willAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SESSION_ID);
            return s;
        });

        QuestionResponse response = service.ask(req, USER_ID);

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo(QUESTION);
        assertThat(response.conversationId()).isEqualTo(SESSION_ID);
    }

    @Test
    void ask_longQuestion_sessionTitleTruncatedWithEllipsis() {
        String longQuestion = "A".repeat(200);
        QuestionRequest req = new QuestionRequest(longQuestion, null, null);
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());
        given(sessionRepository.save(any(ChatSession.class))).willAnswer(inv -> inv.getArgument(0));

        service.ask(req, USER_ID);

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(captor.capture());
        String title = captor.getValue().getTitle();
        assertThat(title).hasSizeLessThanOrEqualTo(ragProperties.getSessionTitleMaxLength() + 1);
        assertThat(title).endsWith("…");
    }

    @Test
    void ask_withConversationId_loadsExistingSession() {
        ChatSession existingSession = ChatSession.builder().build();
        ReflectionTestUtils.setField(existingSession, "id", SESSION_ID);
        QuestionRequest req = new QuestionRequest(QUESTION, null, SESSION_ID);

        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.of(existingSession));
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        QuestionResponse response = service.ask(req, USER_ID);

        verify(sessionRepository, never()).save(any());
        assertThat(response.conversationId()).isEqualTo(SESSION_ID);
    }

    @Test
    void ask_conversationIdNotFound_throwsException() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, SESSION_ID);
        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.ask(req, USER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(SESSION_ID.toString());
    }

    // ── message persistence ───────────────────────────────────────────────────

    @Test
    void ask_persistsUserMessageThenAssistantMessage() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        stubNewSession();
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        service.ask(req, USER_ID);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertThat(saved.get(0).getRole()).isEqualTo(ChatMessage.Role.USER);
        assertThat(saved.get(0).getContent()).isEqualTo(QUESTION);
        assertThat(saved.get(1).getRole()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(saved.get(1).getContent()).isEqualTo(ANSWER);
    }

    // ── confidence score ──────────────────────────────────────────────────────

    @Test
    void ask_confidenceIsAverageOfChunkSimilarities() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        stubNewSession();
        List<SimilarChunk> chunks = List.of(
                chunk(0.9), chunk(0.7), chunk(0.5));
        given(vectorRepository.findSimilar(any(), any())).willReturn(chunks);
        given(documentRepository.findAllById(any())).willReturn(List.of());

        QuestionResponse response = service.ask(req, USER_ID);

        // (0.9 + 0.7 + 0.5) / 3 = 0.7
        assertThat(response.confidence()).isCloseTo(0.7, within(1e-9));
    }

    @Test
    void ask_confidenceIsZero_whenNoChunksFound() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        stubNewSession();
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        QuestionResponse response = service.ask(req, USER_ID);

        assertThat(response.confidence()).isZero();
    }

    @Test
    void ask_confidenceClampedToOne_whenSimilarityExceedsRange() {
        assertThat(QAService.computeConfidence(List.of(chunk(1.5)))).isLessThanOrEqualTo(1.0);
    }

    // ── source chunks ─────────────────────────────────────────────────────────

    @Test
    void ask_sourcesIncludeDocumentTitleAndScore() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        stubNewSession();
        SimilarChunk c = new SimilarChunk(UUID.randomUUID(), DOC_ID, 2, "Chunk text", 0.85);
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of(c));
        Document doc = stubDocument(DOC_ID, "My Report");
        given(documentRepository.findAllById(any())).willReturn(List.of(doc));

        QuestionResponse response = service.ask(req, USER_ID);

        assertThat(response.sources()).hasSize(1);
        QuestionResponse.SourceChunk src = response.sources().get(0);
        assertThat(src.documentId()).isEqualTo(DOC_ID);
        assertThat(src.documentTitle()).isEqualTo("My Report");
        assertThat(src.chunkIndex()).isEqualTo(2);
        assertThat(src.content()).isEqualTo("Chunk text");
        assertThat(src.score()).isCloseTo(0.85, within(1e-9));
    }

    @Test
    void ask_returnsAnswerFromLlm() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        stubNewSession();
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        QuestionResponse response = service.ask(req, USER_ID);

        assertThat(response.answer()).isEqualTo(ANSWER);
    }

    @Test
    void ask_passesSystemAndUserPromptToLlm() {
        QuestionRequest req = new QuestionRequest(QUESTION, null, null);
        stubNewSession();
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        service.ask(req, USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(chatLanguageModel).generate(captor.capture());
        List<dev.langchain4j.data.message.ChatMessage> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).toString()).contains("document Q&A assistant");
        assertThat(messages.get(1).toString()).contains(QUESTION);
    }

    // ── computeConfidence unit tests ──────────────────────────────────────────

    @Test
    void computeConfidence_emptyList_returnsZero() {
        assertThat(QAService.computeConfidence(List.of())).isZero();
    }

    @Test
    void computeConfidence_singleChunk_returnsSimilarity() {
        assertThat(QAService.computeConfidence(List.of(chunk(0.6))))
                .isCloseTo(0.6, within(1e-9));
    }

    @Test
    void computeConfidence_negativeSimilarity_clampedToZero() {
        // similarity < 0 can occur for near-orthogonal or opposite vectors
        assertThat(QAService.computeConfidence(List.of(chunk(-0.5), chunk(-0.3))))
                .isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubNewSession() {
        given(sessionRepository.save(any(ChatSession.class))).willAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SESSION_ID);
            return s;
        });
    }

    private static SimilarChunk chunk(double similarity) {
        return new SimilarChunk(UUID.randomUUID(), DOC_ID, 0, "content", similarity);
    }

    private static Document stubDocument(UUID id, String title) {
        Document doc = Document.builder().title(title).build();
        ReflectionTestUtils.setField(doc, "id", id);
        return doc;
    }
}
