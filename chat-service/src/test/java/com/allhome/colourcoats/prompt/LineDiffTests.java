package com.allhome.colourcoats.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.allhome.colourcoats.prompt.LineDiff.Kind;
import com.allhome.colourcoats.prompt.LineDiff.Line;
import org.junit.jupiter.api.Test;

class LineDiffTests {

	@Test
	void showsRemovedAndAddedLinesInReadingOrder() {
		assertThat(LineDiff.between("keep\nold rule\nalso keep", "keep\nnew rule\nalso keep\nextra"))
			.containsExactly(new Line(Kind.SAME, "keep"), new Line(Kind.REMOVED, "old rule"),
					new Line(Kind.ADDED, "new rule"), new Line(Kind.SAME, "also keep"), new Line(Kind.ADDED, "extra"));
	}

	@Test
	void identicalTextsAndLineEndingsGiveNoChanges() {
		assertThat(LineDiff.between("a\r\nb\n", "a\nb")).extracting(Line::kind).containsOnly(Kind.SAME);
		assertThat(LineDiff.between("", "new")).containsExactly(new Line(Kind.ADDED, "new"));
	}

}
