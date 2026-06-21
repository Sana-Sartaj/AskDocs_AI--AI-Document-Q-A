package com.docqa.repository;

import com.docqa.model.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Page<ChatSession> findByUserIdOrderByUpdatedAtDesc(UUID userId, Pageable pageable);

    Optional<ChatSession> findByIdAndUserId(UUID id, UUID userId);
}
