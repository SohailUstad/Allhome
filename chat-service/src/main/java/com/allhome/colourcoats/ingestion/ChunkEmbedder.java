package com.allhome.colourcoats.ingestion;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/** Embeds chunk texts in batches and checks the model returned one vector of the expected size per chunk. */
@Component
class ChunkEmbedder {

	private final EmbeddingModel embeddingModel;

	private final int batchSize;

	ChunkEmbedder(EmbeddingModel embeddingModel, IngestionProperties properties) {
		this.embeddingModel = embeddingModel;
		this.batchSize = properties.embeddingBatchSize();
	}

	/** @return one embedding per chunk, in the same order */
	List<float[]> embed(List<KnowledgeChunk> chunks) {
		List<float[]> embeddings = new ArrayList<>(chunks.size());
		for (int start = 0; start < chunks.size(); start += batchSize) {
			List<String> texts = chunks.subList(start, Math.min(start + batchSize, chunks.size()))
				.stream()
				.map(KnowledgeChunk::text)
				.toList();
			List<float[]> batch = embeddingModel.embed(texts);
			if (batch.size() != texts.size()) {
				throw new IllegalStateException(
						"Embedding model returned %d vectors for %d texts".formatted(batch.size(), texts.size()));
			}
			for (float[] vector : batch) {
				if (vector.length != KnowledgeChunkEntity.EMBEDDING_DIMENSIONS) {
					throw new IllegalStateException("Embedding model returned %d dimensions, expected %d"
						.formatted(vector.length, KnowledgeChunkEntity.EMBEDDING_DIMENSIONS));
				}
			}
			embeddings.addAll(batch);
		}
		return embeddings;
	}

}
