package com.docqa.service;

import com.docqa.config.DocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    private TextChunker chunker;
    private static final int CHUNK_SIZE = 100;
    private static final int OVERLAP = 20;

    @BeforeEach
    void setUp() {
        DocumentProperties props = new DocumentProperties();
        props.setChunkSize(CHUNK_SIZE);
        props.setChunkOverlap(OVERLAP);
        chunker = new TextChunker(props);
    }

    // ── guard clauses ─────────────────────────────────────────────────────────

    @Test
    void split_returnsEmpty_whenTextIsNull() {
        assertThat(chunker.split(null, 100, 20)).isEmpty();
    }

    @Test
    void split_returnsEmpty_whenTextIsBlank() {
        assertThat(chunker.split("   \n\t  ", 100, 20)).isEmpty();
    }

    @Test
    void split_throws_whenOverlapEqualsChunkSize() {
        assertThatThrownBy(() -> chunker.split("some text", 50, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void split_throws_whenOverlapExceedsChunkSize() {
        assertThatThrownBy(() -> chunker.split("some text", 50, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── short-text fast path ──────────────────────────────────────────────────

    @Test
    void split_returnsSingleChunk_whenTextFitsInChunkSize() {
        String text = "Short text that fits.";
        List<String> chunks = chunker.split(text, 100, 20);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    void split_returnsSingleChunk_whenTextExactlyEqualsChunkSize() {
        String text = "A".repeat(100);
        List<String> chunks = chunker.split(text, 100, 20);
        assertThat(chunks).hasSize(1);
    }

    // ── paragraph-level splitting ─────────────────────────────────────────────

    @Test
    void split_splitsOnParagraphBreaks_first() {
        // Three paragraphs, each < 100 chars; two together exceed 100
        String p1 = "First paragraph with some content here.";        // 40
        String p2 = "Second paragraph with some content here.";       // 41
        String p3 = "Third paragraph with some content here.";        // 40
        String text = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = chunker.split(text, 100, 20);

        assertThat(chunks).isNotEmpty();
        // Every chunk must fit within the size limit
        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(CHUNK_SIZE));
        // Paragraphs should appear in the output without being split mid-word
        assertThat(String.join(" ", chunks)).contains(p1.strip());
        assertThat(String.join(" ", chunks)).contains(p3.strip());
    }

    // ── sentence-level splitting ──────────────────────────────────────────────

    @Test
    void split_splitsOnSentences_whenParagraphsAreAbsent() {
        // Single block of text with multiple sentences, total > 100 chars
        String text = "The quick brown fox jumps over the lazy dog. "
                + "A second sentence follows here. "
                + "And then a third one appears.";

        List<String> chunks = chunker.split(text, 60, 10);

        assertThat(chunks.size()).isGreaterThan(1);
        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(60));
    }

    // ── word-level splitting ──────────────────────────────────────────────────

    @Test
    void split_splitsOnWords_whenNoSentenceBoundaries() {
        // Long text made of space-separated words but no sentence punctuation
        String text = "word ".repeat(30).strip(); // 150 chars

        List<String> chunks = chunker.split(text, 50, 10);

        assertThat(chunks.size()).isGreaterThan(1);
        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(50));
    }

    // ── character-level fallback ──────────────────────────────────────────────

    @Test
    void split_fallsBackToCharacterLevel_whenNoSeparatorsPresent() {
        // A single "word" longer than chunkSize with no spaces or punctuation
        String text = "A".repeat(250);

        List<String> chunks = chunker.split(text, 100, 20);

        assertThat(chunks.size()).isGreaterThan(1);
        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(100));
    }

    // ── size and coverage invariants ──────────────────────────────────────────

    @Test
    void split_allChunksRespectChunkSize() {
        String text = buildMixedText(2000);
        List<String> chunks = chunker.split(text, 200, 40);
        chunks.forEach(c ->
                assertThat(c.length())
                        .as("chunk length must be ≤ 200 but was %d: [%s]", c.length(), c)
                        .isLessThanOrEqualTo(200));
    }

    @Test
    void split_producesMultipleChunks_forLongText() {
        String text = buildMixedText(2000);
        List<String> chunks = chunker.split(text, 200, 40);
        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    void split_noChunkIsBlank() {
        String text = buildMixedText(1500);
        List<String> chunks = chunker.split(text, 200, 40);
        chunks.forEach(c -> assertThat(c).isNotBlank());
    }

    // ── overlap ───────────────────────────────────────────────────────────────

    @Test
    void split_adjacentChunksShareOverlapContent() {
        // Use word splitting so overlap is predictable
        String text = "word ".repeat(40).strip(); // 200 chars, chunkSize=50 → ~5 chunks
        List<String> chunks = chunker.split(text, 50, 15);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i + 1 < chunks.size(); i++) {
            String current = chunks.get(i);
            String next = chunks.get(i + 1);
            // At least one token from the tail of current must appear at the start of next
            String[] currentWords = current.split(" ");
            String lastWord = currentWords[currentWords.length - 1];
            assertThat(next).as("chunk %d should share content with chunk %d", i, i + 1)
                    .contains(lastWord);
        }
    }

    // ── default-properties delegation ─────────────────────────────────────────

    @Test
    void split_singleArgUsesConfiguredDefaults() {
        DocumentProperties props = new DocumentProperties();
        props.setChunkSize(50);
        props.setChunkOverlap(10);
        TextChunker tc = new TextChunker(props);

        String text = "Hello world. ".repeat(20);
        List<String> chunks = tc.split(text);

        assertThat(chunks).isNotEmpty();
        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(50));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a realistic mixed text (paragraphs + sentences) of approximately
     * {@code targetLength} characters.
     */
    private static String buildMixedText(int targetLength) {
        String sentence = "The quick brown fox jumps over the lazy dog. ";
        String paragraph = sentence.repeat(5);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < targetLength) {
            sb.append(paragraph).append("\n\n");
        }
        return sb.toString().strip();
    }
}
