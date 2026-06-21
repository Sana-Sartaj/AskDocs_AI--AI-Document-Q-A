package com.docqa.service;

import com.docqa.config.RagProperties;
import com.docqa.dto.request.QuestionRequest;
import com.docqa.dto.response.ChatSessionSummary;
import com.docqa.dto.response.ConversationResponse;
import com.docqa.dto.response.QuestionResponse;
import com.docqa.exception.ApiException;
import com.docqa.model.ChatMessage;
import com.docqa.model.ChatSession;
import com.docqa.model.Document;
import com.docqa.observability.DocQaMetrics;
import com.docqa.repository.ChatMessageRepository;
import com.docqa.repository.ChatSessionRepository;
import com.docqa.repository.DocumentRepository;
import com.docqa.repository.UserRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatLanguageModel chatLanguageModel;
    private final VectorRepository vectorRepository;
    private final DocumentRepository documentRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final PromptBuilder promptBuilder;
    private final RagProperties ragProperties;
    private final DocQaMetrics metrics;

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Answers a question with full conversation history fed back into the LLM.
     * Retrieves relevant chunks for the <em>current</em> question on every turn
     * so the RAG context stays focused, while the conversation history provides
     * continuity.
     */
    @Transactional
    public QuestionResponse ask(QuestionRequest request, UUID userId) {
        metrics.chatAsked();

        // 1. Validate document ownership and determine search scope
        List<UUID> scopedDocIds = validateAndResolveDocIds(request, userId);

        // 2. Find or create chat session
        ChatSession session = resolveSession(request, userId, scopedDocIds);

        // 3. Retrieve semantically relevant chunks for this turn
        List<SimilarChunk> chunks = scopedDocIds != null
                ? vectorRepository.findSimilarInDocuments(request.question(), scopedDocIds)
                : vectorRepository.findSimilar(request.question(), userId);

        // 4. Resolve document titles for source attribution
        Map<UUID, String> docTitles = loadDocumentTitles(chunks);

        // 5. Load previous messages and call LLM with full context + history
        List<ChatMessage> history =
                messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        Timer.Sample llmSample = metrics.startLlmCall();
        String answer;
        try {
            answer = generateWithHistory(request.question(), chunks, docTitles, history);
        } finally {
            metrics.stopLlmCall(llmSample);
        }

        log.debug("Chat: userId={}, sessionId={}, historyMessages={}, chunks={}",
                userId, session.getId(), history.size(), chunks.size());

        // 6. Persist both turns
        persistMessage(session, ChatMessage.Role.USER, request.question());
        ChatMessage assistantMsg = persistMessage(session, ChatMessage.Role.ASSISTANT, answer);

        // 7. Build response with citations and confidence
        double confidence = computeConfidence(chunks);
        return new QuestionResponse(
                session.getId(),
                assistantMsg.getId(),
                answer,
                toSourceChunks(chunks, docTitles),
                confidence,
                assistantMsg.getCreatedAt());
    }

    /** Returns a paginated list of the user's chat sessions, newest first. */
    @Transactional(readOnly = true)
    public Page<ChatSessionSummary> listSessions(UUID userId, Pageable pageable) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId, pageable)
                .map(s -> new ChatSessionSummary(
                        s.getId(),
                        s.getTitle(),
                        messageRepository.countBySessionId(s.getId()),
                        s.getCreatedAt(),
                        s.getUpdatedAt()));
    }

    /** Returns a session with its full message history. */
    @Transactional(readOnly = true)
    public ConversationResponse getSession(UUID sessionId, UUID userId) {
        ChatSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Conversation not found: " + sessionId));
        List<ConversationResponse.MessageResponse> messages =
                messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                        .map(m -> new ConversationResponse.MessageResponse(
                                m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt()))
                        .toList();
        return new ConversationResponse(
                session.getId(), session.getTitle(), messages,
                session.getCreatedAt(), session.getUpdatedAt());
    }

    /** Hard-deletes a session and all its messages (via cascade). */
    @Transactional
    public void deleteSession(UUID sessionId, UUID userId) {
        ChatSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Conversation not found: " + sessionId));
        sessionRepository.delete(session);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a multi-turn LangChain4j message list and calls the LLM.
     *
     * Structure:
     *   [SystemMessage: instructions + RAG context for this turn]
     *   [UserMessage:   turn 1 question  ]  ← from history
     *   [AiMessage:     turn 1 answer    ]
     *   ...
     *   [UserMessage:   current question ]  ← always last
     *
     * History is capped at {@code ragProperties.getMaxHistoryMessages()} to
     * avoid overflowing the model's context window.
     */
    private String generateWithHistory(
            String question,
            List<SimilarChunk> chunks,
            Map<UUID, String> docTitles,
            List<ChatMessage> history) {

        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages = new ArrayList<>();

        // Embed RAG context in the system message so it applies to every turn
        String contextSection = promptBuilder.buildContextSection(
                chunks, docTitles, ragProperties.getMaxContextChars());
        lc4jMessages.add(SystemMessage.from(
                promptBuilder.buildSystemPrompt() + "\n\nRelevant context:\n\n" + contextSection));

        // Replay previous turns (oldest first, capped)
        int maxHistory = ragProperties.getMaxHistoryMessages();
        List<ChatMessage> window = history.size() <= maxHistory
                ? history
                : history.subList(history.size() - maxHistory, history.size());

        for (ChatMessage msg : window) {
            switch (msg.getRole()) {
                case USER      -> lc4jMessages.add(UserMessage.from(msg.getContent()));
                case ASSISTANT -> lc4jMessages.add(AiMessage.from(msg.getContent()));
                case SYSTEM    -> { /* already handled by the system message above */ }
            }
        }

        // Current question is always the final user turn
        lc4jMessages.add(UserMessage.from(question));

        return chatLanguageModel.generate(lc4jMessages).content().text();
    }

    private List<UUID> validateAndResolveDocIds(QuestionRequest request, UUID userId) {
        if (request.documentIds() == null || request.documentIds().isEmpty()) {
            return null;
        }
        List<Document> owned = documentRepository.findByIdInAndUserId(request.documentIds(), userId);
        if (owned.size() != request.documentIds().size()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "One or more documents not found or not accessible");
        }
        return request.documentIds();
    }

    private ChatSession resolveSession(
            QuestionRequest request, UUID userId, List<UUID> scopedDocIds) {
        if (request.conversationId() != null) {
            return sessionRepository.findByIdAndUserId(request.conversationId(), userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "Conversation not found: " + request.conversationId()));
        }
        String q = request.question();
        int max = ragProperties.getSessionTitleMaxLength();
        String title = q.length() <= max ? q : q.substring(0, max) + "…";
        return sessionRepository.save(ChatSession.builder()
                .user(userRepository.getReferenceById(userId))
                .title(title)
                .documentIds(scopedDocIds != null ? scopedDocIds.toArray(UUID[]::new) : null)
                .build());
    }

    private Map<UUID, String> loadDocumentTitles(List<SimilarChunk> chunks) {
        if (chunks.isEmpty()) return Map.of();
        Set<UUID> ids = chunks.stream()
                .map(SimilarChunk::documentId)
                .collect(Collectors.toSet());
        return documentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Document::getId, Document::getTitle));
    }

    private ChatMessage persistMessage(ChatSession session, ChatMessage.Role role, String content) {
        return messageRepository.save(ChatMessage.builder()
                .session(session)
                .role(role)
                .content(content)
                .build());
    }

    static double computeConfidence(List<SimilarChunk> chunks) {
        if (chunks.isEmpty()) return 0.0;
        return Math.max(0.0, Math.min(1.0,
                chunks.stream().mapToDouble(SimilarChunk::similarity).average().orElse(0.0)));
    }

    private static List<QuestionResponse.SourceChunk> toSourceChunks(
            List<SimilarChunk> chunks, Map<UUID, String> docTitles) {
        return chunks.stream()
                .map(c -> new QuestionResponse.SourceChunk(
                        c.documentId(),
                        docTitles.getOrDefault(c.documentId(), "Unknown"),
                        c.chunkIndex(),
                        c.content(),
                        c.similarity()))
                .toList();
    }
}
