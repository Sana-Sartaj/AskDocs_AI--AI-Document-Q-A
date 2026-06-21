package com.docqa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Central registry for all DocQA business metrics.
 *
 * Counters and timers are created once at startup and reused on every call —
 * this is the correct Micrometer pattern (never create meters inside hot paths).
 *
 * All metrics are prefixed with {@code docqa.} so they are easy to find in
 * Prometheus / Grafana dashboards.
 */
@Component
public class DocQaMetrics {

    // ── Counters ──────────────────────────────────────────────────────────────

    private final Counter documentsUploaded;
    private final Counter documentsProcessed;
    private final Counter documentsFailed;
    private final Counter chunksCreated;
    private final Counter embeddingsGenerated;
    private final Counter questionsAsked;
    private final Counter chatsAsked;

    // ── Timers ────────────────────────────────────────────────────────────────

    private final Timer pdfProcessingTimer;
    private final Timer embeddingBatchTimer;
    private final Timer vectorSearchTimer;
    private final Timer llmCallTimer;

    public DocQaMetrics(MeterRegistry registry) {
        this.documentsUploaded = Counter.builder("docqa.documents.uploaded")
                .description("Total number of documents uploaded by users")
                .register(registry);

        this.documentsProcessed = Counter.builder("docqa.documents.processed")
                .description("Total number of documents successfully processed (PDF parsed + embedded)")
                .register(registry);

        this.documentsFailed = Counter.builder("docqa.documents.failed")
                .description("Total number of documents that failed processing")
                .register(registry);

        this.chunksCreated = Counter.builder("docqa.chunks.created")
                .description("Total text chunks created across all processed documents")
                .register(registry);

        this.embeddingsGenerated = Counter.builder("docqa.embeddings.generated")
                .description("Total embedding vectors generated and persisted")
                .register(registry);

        this.questionsAsked = Counter.builder("docqa.questions.asked")
                .description("Total one-shot questions answered via /qa/ask")
                .register(registry);

        this.chatsAsked = Counter.builder("docqa.chats.asked")
                .description("Total multi-turn messages answered via /chat/ask")
                .register(registry);

        this.pdfProcessingTimer = Timer.builder("docqa.pdf.processing.duration")
                .description("End-to-end time to parse, chunk, and embed a PDF")
                .publishPercentileHistogram()
                .register(registry);

        this.embeddingBatchTimer = Timer.builder("docqa.embedding.batch.duration")
                .description("Time to generate and persist one batch of embeddings")
                .publishPercentileHistogram()
                .register(registry);

        this.vectorSearchTimer = Timer.builder("docqa.vector.search.duration")
                .description("Time to embed the query and run the pgvector similarity search")
                .publishPercentileHistogram()
                .register(registry);

        this.llmCallTimer = Timer.builder("docqa.llm.call.duration")
                .description("Round-trip time for an OpenAI chat completion call")
                .publishPercentileHistogram()
                .register(registry);
    }

    // ── Counter methods ───────────────────────────────────────────────────────

    public void documentUploaded()              { documentsUploaded.increment(); }
    public void documentProcessed()            { documentsProcessed.increment(); }
    public void documentFailed()               { documentsFailed.increment(); }
    public void chunksCreated(int count)       { chunksCreated.increment(count); }
    public void embeddingsGenerated(int count) { embeddingsGenerated.increment(count); }
    public void questionAsked()                { questionsAsked.increment(); }
    public void chatAsked()                    { chatsAsked.increment(); }

    // ── Timer sample factories ────────────────────────────────────────────────

    public Timer.Sample startPdfProcessing()   { return Timer.start(); }
    public Timer.Sample startEmbeddingBatch()  { return Timer.start(); }
    public Timer.Sample startVectorSearch()    { return Timer.start(); }
    public Timer.Sample startLlmCall()         { return Timer.start(); }

    public void stopPdfProcessing(Timer.Sample s)  { s.stop(pdfProcessingTimer); }
    public void stopEmbeddingBatch(Timer.Sample s) { s.stop(embeddingBatchTimer); }
    public void stopVectorSearch(Timer.Sample s)   { s.stop(vectorSearchTimer); }
    public void stopLlmCall(Timer.Sample s)        { s.stop(llmCallTimer); }
}
