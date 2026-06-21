package com.docqa.repository;

import com.docqa.model.Document;
import com.docqa.model.Document.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Document> findByIdAndUserId(UUID id, UUID userId);

    List<Document> findByIdInAndUserId(Collection<UUID> ids, UUID userId);

    long countByUserIdAndStatus(UUID userId, Status status);
}
