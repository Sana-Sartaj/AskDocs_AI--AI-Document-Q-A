package com.docqa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocQaMetricsTest {

    private SimpleMeterRegistry registry;
    private DocQaMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DocQaMetrics(registry);
    }

    @Test
    void documentUploaded_incrementsCounter() {
        metrics.documentUploaded();
        metrics.documentUploaded();

        Counter counter = registry.find("docqa.documents.uploaded").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void documentProcessedAndFailed_incrementsSeparateCounters() {
        metrics.documentProcessed();
        metrics.documentFailed();
        metrics.documentFailed();

        assertThat(registry.find("docqa.documents.processed").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("docqa.documents.failed").counter().count()).isEqualTo(2.0);
    }

    @Test
    void chunksCreated_incrementsByCount() {
        metrics.chunksCreated(42);
        metrics.chunksCreated(8);

        assertThat(registry.find("docqa.chunks.created").counter().count()).isEqualTo(50.0);
    }

    @Test
    void embeddingsGenerated_incrementsByCount() {
        metrics.embeddingsGenerated(100);

        assertThat(registry.find("docqa.embeddings.generated").counter().count()).isEqualTo(100.0);
    }

    @Test
    void questionsAndChatsAsked_trackSeparately() {
        metrics.questionAsked();
        metrics.questionAsked();
        metrics.chatAsked();

        assertThat(registry.find("docqa.questions.asked").counter().count()).isEqualTo(2.0);
        assertThat(registry.find("docqa.chats.asked").counter().count()).isEqualTo(1.0);
    }

    @Test
    void timerStartStop_recordsDuration() {
        Timer.Sample sample = metrics.startLlmCall();
        metrics.stopLlmCall(sample);

        Timer timer = registry.find("docqa.llm.call.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void allTimersRegistered_atStartup() {
        assertThat(registry.find("docqa.pdf.processing.duration").timer()).isNotNull();
        assertThat(registry.find("docqa.embedding.batch.duration").timer()).isNotNull();
        assertThat(registry.find("docqa.vector.search.duration").timer()).isNotNull();
        assertThat(registry.find("docqa.llm.call.duration").timer()).isNotNull();
    }
}
