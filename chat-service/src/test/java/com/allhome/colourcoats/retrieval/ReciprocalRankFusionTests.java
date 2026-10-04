package com.allhome.colourcoats.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;

import com.allhome.colourcoats.retrieval.RetrievedChunk.Match;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTests {

	private final RetrievedChunk a = chunk("a");

	private final RetrievedChunk b = chunk("b");

	private final RetrievedChunk c = chunk("c");

	@Test
	void chunkFoundByBothSearchesRanksAboveSingleFirstPlaces() {
		// a is first in vector search only, c first in keyword search only, b second in both.
		List<RetrievedChunk> fused = ReciprocalRankFusion.fuse(List.of(a, b), List.of(c, b), 10);

		assertThat(fused).extracting(RetrievedChunk::text).containsExactly("b", "a", "c");
		assertThat(fused.get(0).match()).isEqualTo(Match.BOTH);
		assertThat(fused.get(0).score()).isCloseTo(2.0 / (ReciprocalRankFusion.K + 2), within(1e-12));
		assertThat(fused.get(1).match()).isEqualTo(Match.VECTOR);
		assertThat(fused.get(2).match()).isEqualTo(Match.KEYWORD);
	}

	@Test
	void tiesKeepVectorOrderAndLimitApplies() {
		List<RetrievedChunk> fused = ReciprocalRankFusion.fuse(List.of(a), List.of(b), 1);

		assertThat(fused).singleElement().extracting(RetrievedChunk::text).isEqualTo("a");
	}

	@Test
	void singleListKeepsItsOrder() {
		assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(c, a, b), 10)).extracting(RetrievedChunk::text)
			.containsExactly("c", "a", "b");
		assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(), 10)).isEmpty();
	}

	private static RetrievedChunk chunk(String text) {
		return new RetrievedChunk(UUID.nameUUIDFromBytes(text.getBytes()), "ds", "v1", text, "https://example.com/",
				null, List.of(), List.of(), 0.5, null, 0);
	}

}
