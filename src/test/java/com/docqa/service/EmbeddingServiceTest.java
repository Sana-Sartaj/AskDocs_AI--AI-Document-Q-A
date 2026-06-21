package com.docqa.service;

import com.docqa.model.DocumentChunk;
import com.docqa.model.Embedding;
import com.docqa.observability.DocQaMetrics;
import com.docqa.repository.EmbeddingRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingRepository embeddingRepository;

    @Mock
    private DocQaMetrics metrics;

    private EmbeddingService service;

    private static final String MODEL_NAME = "text-embedding-3-small";
    private static final float[] VECTOR = {0.1f, 0.2f, 0.3f, 0.4f};

    @BeforeEach
    void setUp() {
        service = new EmbeddingService(embeddingModel, embeddingRepository, metrics);
        ReflectionTestUtils.setField(service, "modelName", MODEL_NAME);
    }

    // ── embed() ───────────────────────────────────────────────────────────────

    @Test
    void embed_returnsVectorFromModel() {
        given(embeddingModel.embed(anyString()))
                .willReturn(Response.from(dev.langchain4j.data.embedding.Embedding.from(VECTOR)));

        float[] result = service.embed("hello world");

        assertThat(result).isEqualTo(VECTOR);
    }

    // ── embedChunks() ─────────────────────────────────────────────────────────

    @Test
    void embedChunks_persistsOneEmbeddingPerChunk() {
        List<DocumentChunk> chunks = makeChunks(3);
        stubEmbedAll(3);

        service.embedChunks(chunks);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Embedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    void embedChunks_setsModelNameOnEachEntity() {
        List<DocumentChunk> chunks = makeChunks(2);
        stubEmbedAll(2);

        service.embedChunks(chunks);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Embedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).saveAll(captor.capture());
        captor.getValue().forEach(e -> assertThat(e.getModel()).isEqualTo(MODEL_NAME));
    }

    @Test
    void embedChunks_setsDimensionToVectorLength() {
        List<DocumentChunk> chunks = makeChunks(1);
        stubEmbedAll(1);

        service.embedChunks(chunks);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Embedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getDimension()).isEqualTo(VECTOR.length);
    }

    @Test
    void embedChunks_linksEmbeddingToChunk() {
        List<DocumentChunk> chunks = makeChunks(1);
        stubEmbedAll(1);

        service.embedChunks(chunks);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Embedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getChunk()).isSameAs(chunks.get(0));
    }

    @Test
    void embedChunks_doesNothing_whenListIsEmpty() {
        service.embedChunks(List.of());

        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(embeddingRepository);
    }

    @Test
    void embedChunks_batchesLargeInputs() {
        int total = EmbeddingService.BATCH_SIZE * 2 + 10; // 210
        List<DocumentChunk> chunks = makeChunks(total);
        given(embeddingModel.embedAll(anyList()))
                .willAnswer(inv -> {
                    List<TextSegment> segs = inv.getArgument(0);
                    List<dev.langchain4j.data.embedding.Embedding> result = segs.stream()
                            .map(seg -> dev.langchain4j.data.embedding.Embedding.from(VECTOR))
                            .toList();
                    return Response.from(result);
                });
        given(embeddingRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        service.embedChunks(chunks);

        // Three batches: 100, 100, 10
        verify(embeddingModel, times(3)).embedAll(anyList());
        verify(embeddingRepository, times(3)).saveAll(anyList());
    }

    @Test
    void embedChunks_firstBatchHasExactlyCappedSize() {
        List<DocumentChunk> chunks = makeChunks(EmbeddingService.BATCH_SIZE + 5);
        given(embeddingModel.embedAll(anyList()))
                .willAnswer(inv -> {
                    List<TextSegment> segs = inv.getArgument(0);
                    List<dev.langchain4j.data.embedding.Embedding> result = segs.stream()
                            .map(seg -> dev.langchain4j.data.embedding.Embedding.from(VECTOR))
                            .toList();
                    return Response.from(result);
                });
        given(embeddingRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        service.embedChunks(chunks);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel, times(2)).embedAll(segCaptor.capture());
        assertThat(segCaptor.getAllValues().get(0)).hasSize(EmbeddingService.BATCH_SIZE);
        assertThat(segCaptor.getAllValues().get(1)).hasSize(5);
    }

    // ── toVectorLiteral() ─────────────────────────────────────────────────────

    @Test
    void toVectorLiteral_formatsAsBracketedCommaList() {
        float[] v = {1.0f, 2.0f, 3.0f};
        assertThat(service.toVectorLiteral(v)).isEqualTo("[1.0,2.0,3.0]");
    }

    @Test
    void toVectorLiteral_handlesHighDimensionalVector() {
        float[] v = new float[1536];
        for (int i = 0; i < v.length; i++) v[i] = i * 0.001f;
        String literal = service.toVectorLiteral(v);
        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal.split(",")).hasSize(1536);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<DocumentChunk> makeChunks(int count) {
        List<DocumentChunk> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(DocumentChunk.builder()
                    .chunkIndex(i)
                    .content("chunk content number " + i)
                    .build());
        }
        return list;
    }

    private void stubEmbedAll(int count) {
        List<dev.langchain4j.data.embedding.Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            embeddings.add(dev.langchain4j.data.embedding.Embedding.from(VECTOR));
        }
        given(embeddingModel.embedAll(anyList())).willReturn(Response.from(embeddings));
        given(embeddingRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
    }
}
