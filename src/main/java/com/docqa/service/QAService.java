package com.docqa.service;

import com.docqa.config.RagProperties;
import com.docqa.dto.request.QuestionRequest;
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
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QAService {

    private final ChatLanguageModel chatLanguageModel;
    private final VectorRepository vectorRepository;
    private final DocumentRepository documentRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final PromptBuilder promptBuilder;
    private final RagProperties ragProperties;
    private final DocQaMetrics metrics;

    @Transactional
    public QuestionResponse ask(QuestionRequest request, UUID userId) {
        metrics.questionAsked();
        // 1. Validate document ownership and resolve scope
        List<UUID> scopedDocIds = validateAndResolveDocIds(request, userId);

        // 2. Find or create chat session
        ChatSession session = resolveSession(request, userId, scopedDocIds);

        // 3. Retrieve semantically relevant chunks
        List<SimilarChunk> chunks = scopedDocIds != null
                ? vectorRepository.findSimilarInDocuments(request.question(), scopedDocIds)
                : vectorRepository.findSimilar(request.question(), userId);

        log.debug("RAG: userId={}, sessionId={}, chunks={}, docScope={}",
                userId, session.getId(), chunks.size(),
                scopedDocIds != null ? "documents(" + scopedDocIds.size() + ")" : "user-wide");

        // 4. Resolve document titles for source attribution
        Map<UUID, String> docTitles = loadDocumentTitles(chunks);

        // 5. Build prompt and call LLM
        String systemPrompt = promptBuilder.buildSystemPrompt();
        String userPrompt = promptBuilder.buildUserPrompt(
                request.question(), chunks, docTitles, ragProperties.getMaxContextChars());
        Timer.Sample llmSample = metrics.startLlmCall();
        String answer;
        try {
            answer = chatLanguageModel.generate(List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
            )).content().text();
        } finally {
            metrics.stopLlmCall(llmSample);
        }

        // 6. Persist both turns of the conversation
        persistMessage(session, ChatMessage.Role.USER, request.question());
        ChatMessage assistantMsg = persistMessage(session, ChatMessage.Role.ASSISTANT, answer);

        // 7. Build response with sources and confidence
        double confidence = computeConfidence(chunks);
        List<QuestionResponse.SourceChunk> sources = toSourceChunks(chunks, docTitles);

        return new QuestionResponse(
                session.getId(),
                assistantMsg.getId(),
                answer,
                sources,
                confidence,
                assistantMsg.getCreatedAt());
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Validates that every requested document belongs to the user.
     * Returns the list of IDs to scope the search, or null for user-wide search.
     */
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

    /**
     * Loads an existing session if {@code conversationId} is set, otherwise
     * creates a new one titled from the first N characters of the question.
     */
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

        ChatSession session = ChatSession.builder()
                .user(userRepository.getReferenceById(userId))
                .title(title)
                .documentIds(scopedDocIds != null ? scopedDocIds.toArray(UUID[]::new) : null)
                .build();
        return sessionRepository.save(session);
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

    /**
     * Confidence is the mean cosine similarity of the retrieved chunks,
     * clamped to [0, 1]. Returns 0.0 when no chunks were found.
     */
    static double computeConfidence(List<SimilarChunk> chunks) {
        if (chunks.isEmpty()) return 0.0;
        double mean = chunks.stream()
                .mapToDouble(SimilarChunk::similarity)
                .average()
                .orElse(0.0);
        return Math.max(0.0, Math.min(1.0, mean));
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
