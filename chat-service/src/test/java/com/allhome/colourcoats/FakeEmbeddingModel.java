package com.allhome.colourcoats;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.allhome.colourcoats.ingestion.KnowledgeChunkEntity;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Embedding model for tests: no network, no cost. The same text always gets the same unit vector. Counts requests
 * and can be told to fail, to test what happens when the real API is down.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

	private final AtomicInteger requests = new AtomicInteger();

	private volatile RuntimeException failure;

	private volatile Function<String, float[]> vectors = FakeEmbeddingModel::vector;

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		requests.incrementAndGet();
		if (failure != null) {
			throw failure;
		}
		List<Embedding> results = new ArrayList<>();
		List<String> texts = request.getInstructions();
		for (int i = 0; i < texts.size(); i++) {
			results.add(new Embedding(vectors.apply(texts.get(i)), i));
		}
		return new EmbeddingResponse(results);
	}

	@Override
	public float[] embed(Document document) {
		return vectors.apply(document.getText());
	}

	/** Decide which vector each text gets, to set up known similarities; {@link #reset()} restores the default. */
	public void useVectors(Function<String, float[]> vectors) {
		this.vectors = vectors;
	}

	public static float[] vector(String text) {
		Random random = new Random(text.hashCode());
		float[] vector = new float[KnowledgeChunkEntity.EMBEDDING_DIMENSIONS];
		double norm = 0;
		for (int i = 0; i < vector.length; i++) {
			vector[i] = (float) random.nextGaussian();
			norm += vector[i] * vector[i];
		}
		for (int i = 0; i < vector.length; i++) {
			vector[i] /= (float) Math.sqrt(norm);
		}
		return vector;
	}

	public int requests() {
		return requests.get();
	}

	public void failWith(RuntimeException failure) {
		this.failure = failure;
	}

	public void reset() {
		requests.set(0);
		failure = null;
		vectors = FakeEmbeddingModel::vector;
	}

}
