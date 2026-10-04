package com.allhome.colourcoats.retrieval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merges ranked result lists by position: each list contributes {@code 1 / (K + rank)} per result. Scores from
 * different searches (cosine similarity, text rank) are not comparable, ranks are. K = 60 is the value from the
 * original RRF paper and the common default; it keeps a single first place from dominating.
 */
final class ReciprocalRankFusion {

	static final int K = 60;

	private ReciprocalRankFusion() {
	}

	/**
	 * @param vectorHits results of the vector search, best first
	 * @param keywordHits results of the keyword search, best first
	 * @param limit maximum results to return
	 * @return fused results, highest score first; ties keep vector order
	 */
	static List<RetrievedChunk> fuse(List<RetrievedChunk> vectorHits, List<RetrievedChunk> keywordHits, int limit) {
		Map<UUID, Fused> fused = new LinkedHashMap<>();
		add(fused, vectorHits, RetrievedChunk.Match.VECTOR);
		add(fused, keywordHits, RetrievedChunk.Match.KEYWORD);
		return fused.values()
			.stream()
			.sorted(Comparator.comparingDouble(Fused::score).reversed())
			.limit(limit)
			.map(Fused::toChunk)
			.toList();
	}

	private static void add(Map<UUID, Fused> fused, List<RetrievedChunk> hits, RetrievedChunk.Match source) {
		for (int rank = 1; rank <= hits.size(); rank++) {
			RetrievedChunk hit = hits.get(rank - 1);
			double contribution = 1.0 / (K + rank);
			fused.merge(hit.id(), new Fused(hit, source, contribution),
					(existing, added) -> new Fused(existing.chunk, RetrievedChunk.Match.BOTH,
							existing.score + added.score));
		}
	}

	private record Fused(RetrievedChunk chunk, RetrievedChunk.Match match, double score) {

		RetrievedChunk toChunk() {
			return chunk.withRanking(match, score);
		}

	}

}
