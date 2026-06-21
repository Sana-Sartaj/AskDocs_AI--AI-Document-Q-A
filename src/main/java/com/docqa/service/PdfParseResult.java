package com.docqa.service;

public record PdfParseResult(
        String text,
        String title,
        String author,
        String subject,
        int pageCount
) {}
