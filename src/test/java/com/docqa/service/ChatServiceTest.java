package com.docqa.service;

import com.docqa.config.RagProperties;
import com.docqa.dto.request.QuestionRequest;
import com.docqa.observability.DocQaMetrics;
import com.docqa.dto.response.ChatSessionSummary;
import com.docqa.dto.response.ConversationResponse;
import com.docqa.dto.response.QuestionResponse;
import com.docqa.exception.ApiException;
import com.docqa.model.ChatMessage;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private VectorRepository vectorRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private ChatSessionRepository sessionRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private DocQaMetrics metrics;

    private ChatService service;

    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID DOC_ID     = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String QUESTION = "What is described in the document?";
    private static final String ANSWER   = "The document describes testing.";

    private final RagProperties ragProperties = new RagProperties();
    private final PromptBuilder promptBuilder = new PromptBuilder();

    @BeforeEach
    void setUp() {
        service = new ChatService(
                chatLanguageModel, vectorRepository, documentRepository,
                sessionRepository, messageRepository, userRepository,
                promptBuilder, ragProperties, metrics);

        // LLM returns ANSWER for any multi-turn call
        given(chatLanguageModel.generate(anyList()))
                .willReturn(Response.from(AiMessage.from(ANSWER)));

        // Save any ChatMessage and stamp it with an id + createdAt
        given(messageRepository.save(any(ChatMessage.class))).willAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(m, "createdAt", Instant.now());
            return m;
        });

        // getReferenceById used when creating a new session
        User user = User.builder().build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        given(userRepository.getReferenceById(USER_ID)).willReturn(user);
    }

    // ── multi-turn call structure ─────────────────────────────────────────────

    @Test
    void ask_firstMessage_callsLlmWithSystemAndOneUserMessage() {
        stubNewSession();
        given(vectorRepository.findSimilar(QUESTION, USER_ID)).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .willReturn(List.of()); // no history yet

        service.ask(new QuestionRequest(QUESTION, null, null), USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(captor.capture());

        List<dev.langchain4j.data.message.ChatMessage> sent = captor.getValue();
        // SystemMessage + current UserMessage = 2
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isInstanceOf(dev.langchain4j.data.message.SystemMessage.class);
        assertThat(sent.get(1)).isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
    }

    @Test
    void ask_withHistory_includesPreviousTurnsBeforeCurrentQuestion() {
        stubExistingSession();
        given(vectorRepository.findSimilar(QUESTION, USER_ID)).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        ChatMessage prevUser = buildPersistedMessage(ChatMessage.Role.USER, "Old question");
        ChatMessage prevAsst = buildPersistedMessage(ChatMessage.Role.ASSISTANT, "Old answer");
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .willReturn(List.of(prevUser, prevAsst));

        service.ask(new QuestionRequest(QUESTION, null, SESSION_ID), USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(captor.capture());

        List<dev.langchain4j.data.message.ChatMessage> sent = captor.getValue();
        // System + 2 history + current = 4
        assertThat(sent).hasSize(4);
        assertThat(sent.get(1)).isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
        assertThat(sent.get(2)).isInstanceOf(dev.langchain4j.data.message.AiMessage.class);
        assertThat(sent.get(3)).isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
    }

    @Test
    void ask_historyExceedsCap_onlyLastNMessagesIncluded() {
        ragProperties.setMaxHistoryMessages(2); // cap at 2 = 1 full turn
        stubExistingSession();
        given(vectorRepository.findSimilar(QUESTION, USER_ID)).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());

        // 4 history messages (2 full turns), but cap = 2 → only last 2 sent
        List<ChatMessage> longHistory = List.of(
                buildPersistedMessage(ChatMessage.Role.USER,      "Q1"),
                buildPersistedMessage(ChatMessage.Role.ASSISTANT, "A1"),
                buildPersistedMessage(ChatMessage.Role.USER,      "Q2"),
                buildPersistedMessage(ChatMessage.Role.ASSISTANT, "A2"));
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .willReturn(longHistory);

        service.ask(new QuestionRequest(QUESTION, null, SESSION_ID), USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(captor.capture());

        // System + 2 capped history + current = 4
        assertThat(captor.getValue()).hasSize(4);
    }

    // ── session management ────────────────────────────────────────────────────

    @Test
    void ask_noConversationId_createsNewSession() {
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).willReturn(List.of());
        given(sessionRepository.save(any(ChatSession.class))).willAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SESSION_ID);
            return s;
        });

        QuestionResponse response =
                service.ask(new QuestionRequest(QUESTION, null, null), USER_ID);

        verify(sessionRepository).save(any(ChatSession.class));
        assertThat(response.conversationId()).isEqualTo(SESSION_ID);
    }

    @Test
    void ask_withConversationId_loadsExistingSession() {
        stubExistingSession();
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .willReturn(List.of());

        service.ask(new QuestionRequest(QUESTION, null, SESSION_ID), USER_ID);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void ask_conversationNotFound_throws404() {
        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.ask(new QuestionRequest(QUESTION, null, SESSION_ID), USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void ask_documentNotOwnedByUser_throws404() {
        given(documentRepository.findByIdInAndUserId(List.of(DOC_ID), USER_ID))
                .willReturn(List.of()); // nothing owned

        assertThatThrownBy(() ->
                service.ask(new QuestionRequest(QUESTION, List.of(DOC_ID), null), USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── persistence ───────────────────────────────────────────────────────────

    @Test
    void ask_persistsUserThenAssistantMessage() {
        stubNewSession();
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of());
        given(documentRepository.findAllById(any())).willReturn(List.of());
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).willReturn(List.of());

        service.ask(new QuestionRequest(QUESTION, null, null), USER_ID);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getRole()).isEqualTo(ChatMessage.Role.USER);
        assertThat(captor.getAllValues().get(1).getRole()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(captor.getAllValues().get(1).getContent()).isEqualTo(ANSWER);
    }

    // ── confidence + sources ──────────────────────────────────────────────────

    @Test
    void ask_confidenceIsAverageOfChunkSimilarities() {
        stubNewSession();
        given(vectorRepository.findSimilar(any(), any()))
                .willReturn(List.of(chunk(0.8), chunk(0.6)));
        given(documentRepository.findAllById(any())).willReturn(List.of());
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).willReturn(List.of());

        QuestionResponse response =
                service.ask(new QuestionRequest(QUESTION, null, null), USER_ID);

        assertThat(response.confidence()).isCloseTo(0.7, within(1e-9));
    }

    @Test
    void ask_sourcesContainDocumentTitleAndScore() {
        stubNewSession();
        SimilarChunk c = new SimilarChunk(UUID.randomUUID(), DOC_ID, 3, "Some text", 0.9);
        given(vectorRepository.findSimilar(any(), any())).willReturn(List.of(c));
        Document doc = buildDocument(DOC_ID, "Annual Report");
        given(documentRepository.findAllById(any())).willReturn(List.of(doc));
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).willReturn(List.of());

        QuestionResponse response =
                service.ask(new QuestionRequest(QUESTION, null, null), USER_ID);

        assertThat(response.sources()).hasSize(1);
        QuestionResponse.SourceChunk src = response.sources().get(0);
        assertThat(src.documentTitle()).isEqualTo("Annual Report");
        assertThat(src.chunkIndex()).isEqualTo(3);
        assertThat(src.score()).isCloseTo(0.9, within(1e-9));
    }

    // ── listSessions ──────────────────────────────────────────────────────────

    @Test
    void listSessions_returnsMappedSummaries() {
        ChatSession s = ChatSession.builder().title("Test session").build();
        ReflectionTestUtils.setField(s, "id", SESSION_ID);
        ReflectionTestUtils.setField(s, "createdAt", Instant.now());
        ReflectionTestUtils.setField(s, "updatedAt", Instant.now());

        Pageable pageable = PageRequest.of(0, 20);
        given(sessionRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(s)));
        given(messageRepository.countBySessionId(SESSION_ID)).willReturn(4L);

        Page<ChatSessionSummary> page = service.listSessions(USER_ID, pageable);

        assertThat(page.getContent()).hasSize(1);
        ChatSessionSummary summary = page.getContent().get(0);
        assertThat(summary.id()).isEqualTo(SESSION_ID);
        assertThat(summary.title()).isEqualTo("Test session");
        assertThat(summary.messageCount()).isEqualTo(4L);
    }

    // ── getSession ────────────────────────────────────────────────────────────

    @Test
    void getSession_returnsConversationWithMessages() {
        ChatSession session = ChatSession.builder().title("History session").build();
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        ReflectionTestUtils.setField(session, "createdAt", Instant.now());
        ReflectionTestUtils.setField(session, "updatedAt", Instant.now());
        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.of(session));

        ChatMessage msg = buildPersistedMessage(ChatMessage.Role.USER, "Hello");
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .willReturn(List.of(msg));

        ConversationResponse response = service.getSession(SESSION_ID, USER_ID);

        assertThat(response.id()).isEqualTo(SESSION_ID);
        assertThat(response.messages()).hasSize(1);
        assertThat(response.messages().get(0).role()).isEqualTo("USER");
        assertThat(response.messages().get(0).content()).isEqualTo("Hello");
    }

    @Test
    void getSession_notFound_throws404() {
        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSession(SESSION_ID, USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── deleteSession ─────────────────────────────────────────────────────────

    @Test
    void deleteSession_deletesSession() {
        ChatSession session = ChatSession.builder().build();
        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.of(session));

        service.deleteSession(SESSION_ID, USER_ID);

        verify(sessionRepository).delete(session);
    }

    @Test
    void deleteSession_notFound_throws404() {
        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSession(SESSION_ID, USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── computeConfidence unit tests ──────────────────────────────────────────

    @Test
    void computeConfidence_empty_returnsZero() {
        assertThat(ChatService.computeConfidence(List.of())).isZero();
    }

    @Test
    void computeConfidence_negativeMean_clampedToZero() {
        assertThat(ChatService.computeConfidence(List.of(chunk(-0.8), chunk(-0.4)))).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubNewSession() {
        given(sessionRepository.save(any(ChatSession.class))).willAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SESSION_ID);
            return s;
        });
        given(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                .willReturn(List.of());
    }

    private void stubExistingSession() {
        ChatSession session = ChatSession.builder().build();
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        given(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .willReturn(Optional.of(session));
    }

    private static SimilarChunk chunk(double similarity) {
        return new SimilarChunk(UUID.randomUUID(), DOC_ID, 0, "content", similarity);
    }

    private static ChatMessage buildPersistedMessage(ChatMessage.Role role, String content) {
        ChatMessage m = ChatMessage.builder().role(role).content(content).build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(m, "createdAt", Instant.now());
        return m;
    }

    private static Document buildDocument(UUID id, String title) {
        Document d = Document.builder().title(title).build();
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }
}
