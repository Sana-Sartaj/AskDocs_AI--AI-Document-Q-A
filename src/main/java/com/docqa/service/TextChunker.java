package com.docqa.service;

import com.docqa.config.DocumentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Recursive character text splitter.
 *
 * Tries separators in priority order — paragraph breaks, line breaks, sentence
 * terminators, commas, spaces, then individual characters — so chunks respect
 * semantic boundaries as much as possible. Adjacent chunks share an overlap
 * window to preserve cross-boundary context.
 */
@Component
@RequiredArgsConstructor
public class TextChunker {

    private static final List<String> SEPARATORS = List.of(
            "\n\n", "\n", ". ", "! ", "? ", "; ", ", ", " ", ""
    );

    private final DocumentProperties documentProperties;

    /** Split using chunk-size and overlap from application configuration. */
    public List<String> split(String text) {
        return split(text, documentProperties.getChunkSize(), documentProperties.getChunkOverlap());
    }

    public List<String> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap (" + overlap + ") must be less than chunkSize (" + chunkSize + ")");
        }
        return splitRecursive(text.strip(), SEPARATORS, chunkSize, overlap)
                .stream()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableList());
    }

    // ── recursive core ────────────────────────────────────────────────────────

    private List<String> splitRecursive(String text, List<String> separators, int chunkSize, int overlap) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        // Pick the first separator that actually appears in the text
        String separator = "";
        List<String> nextSeparators = Collections.emptyList();
        for (int i = 0; i < separators.size(); i++) {
            String sep = separators.get(i);
            if (sep.isEmpty() || text.contains(sep)) {
                separator = sep;
                nextSeparators = separators.subList(i + 1, separators.size());
                break;
            }
        }

        List<String> rawSplits = applySplit(text, separator);

        List<String> goodSplits = new ArrayList<>();
        List<String> result = new ArrayList<>();

        for (String piece : rawSplits) {
            if (piece.length() > chunkSize) {
                // Flush accumulated small pieces, then recurse on the large one
                result.addAll(mergeSplits(goodSplits, separator, chunkSize, overlap));
                goodSplits.clear();
                result.addAll(splitRecursive(piece, nextSeparators, chunkSize, overlap));
            } else {
                goodSplits.add(piece);
            }
        }

        result.addAll(mergeSplits(goodSplits, separator, chunkSize, overlap));
        return result;
    }

    private static List<String> applySplit(String text, String separator) {
        List<String> parts;
        if (separator.isEmpty()) {
            // Character-level fallback: split into individual chars
            parts = text.chars()
                    .mapToObj(c -> String.valueOf((char) c))
                    .collect(Collectors.toCollection(ArrayList::new));
        } else {
            parts = new ArrayList<>(Arrays.asList(text.split(Pattern.quote(separator), -1)));
        }
        parts.removeIf(String::isEmpty);
        return parts;
    }

    /**
     * Merges a list of small splits into chunks of at most {@code chunkSize},
     * keeping a trailing overlap window so adjacent chunks share context.
     *
     * Invariant: every element in {@code splits} has length ≤ chunkSize.
     */
    private static List<String> mergeSplits(
            List<String> splits, String separator, int chunkSize, int overlap) {

        if (splits.isEmpty()) {
            return Collections.emptyList();
        }

        int sepLen = separator.length();
        List<String> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int total = 0; // == String.join(separator, current).length()

        for (String d : splits) {
            int dLen = d.length();
            int wouldBe = total + (current.isEmpty() ? 0 : sepLen) + dLen;

            if (wouldBe > chunkSize && !current.isEmpty()) {
                result.add(String.join(separator, current));

                // Trim the front of the window until we're within the overlap budget
                while (!current.isEmpty()
                        && (total > overlap
                        || (total + (current.isEmpty() ? 0 : sepLen) + dLen > chunkSize
                        && total > 0))) {
                    int removed = current.remove(0).length();
                    total -= removed + (current.isEmpty() ? 0 : sepLen);
                }
            }

            current.add(d);
            total += dLen + (current.size() > 1 ? sepLen : 0);
        }

        if (!current.isEmpty()) {
            result.add(String.join(separator, current));
        }

        return result;
    }
}
