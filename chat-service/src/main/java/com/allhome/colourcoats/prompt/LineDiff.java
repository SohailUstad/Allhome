package com.allhome.colourcoats.prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Line-by-line difference between two versions of a section, for showing a change before it is accepted. */
public final class LineDiff {

	/** Above this many line pairs the texts are shown as fully replaced instead of compared line by line. */
	private static final int MAX_CELLS = 250_000;

	public enum Kind {

		SAME, ADDED, REMOVED

	}

	public record Line(Kind kind, String text) {

	}

	private LineDiff() {
	}

	/** The lines of {@code after}, plus the removed lines of {@code before}, in reading order (longest common subsequence). */
	public static List<Line> between(String before, String after) {
		String[] a = lines(before);
		String[] b = lines(after);
		if ((long) a.length * b.length > MAX_CELLS) {
			List<Line> replaced = new ArrayList<>();
			for (String line : a) {
				replaced.add(new Line(Kind.REMOVED, line));
			}
			for (String line : b) {
				replaced.add(new Line(Kind.ADDED, line));
			}
			return replaced;
		}
		int[][] common = new int[a.length + 1][b.length + 1];
		for (int i = a.length - 1; i >= 0; i--) {
			for (int j = b.length - 1; j >= 0; j--) {
				common[i][j] = a[i].equals(b[j]) ? common[i + 1][j + 1] + 1
						: Math.max(common[i + 1][j], common[i][j + 1]);
			}
		}
		List<Line> result = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < a.length && j < b.length) {
			if (a[i].equals(b[j])) {
				result.add(new Line(Kind.SAME, a[i]));
				i++;
				j++;
			}
			else if (common[i + 1][j] >= common[i][j + 1]) {
				result.add(new Line(Kind.REMOVED, a[i++]));
			}
			else {
				result.add(new Line(Kind.ADDED, b[j++]));
			}
		}
		while (i < a.length) {
			result.add(new Line(Kind.REMOVED, a[i++]));
		}
		while (j < b.length) {
			result.add(new Line(Kind.ADDED, b[j++]));
		}
		return Collections.unmodifiableList(result);
	}

	private static String[] lines(String text) {
		String normalised = PromptDocument.normalise(text).strip();
		return normalised.isEmpty() ? new String[0] : normalised.split("\n", -1);
	}

}
