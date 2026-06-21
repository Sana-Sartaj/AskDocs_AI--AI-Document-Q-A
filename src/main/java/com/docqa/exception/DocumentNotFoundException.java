package com.docqa.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class DocumentNotFoundException extends ApiException {

    public DocumentNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "Document not found: " + id);
    }
}
