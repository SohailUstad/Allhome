package com.allhome.colourcoats.prompt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits the system prompt into sections at its level-2 headings ({@code ## }) and joins sections back into the text
 * the model receives. Deeper headings ({@code ###}) stay inside their section.
 */
public final class PromptDocument {

	/** Key of the text before the first heading (who the assistant is). */
	public static final String PREAMBLE_KEY = "role";

	private static final Pattern HEADING = Pattern.compile("^## (.+)$");

	private static final Pattern NUMBERING = Pattern.compile("^\\d+\\.\\s*");

	private PromptDocument() {
	}

	public static List<PromptSection> parse(String markdown) {
		List<PromptSection> sections = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		String title = null;
		StringBuilder body = new StringBuilder();
		boolean preamble = true;
		for (String line : normalise(markdown).split("\n", -1)) {
			var heading = HEADING.matcher(line);
			if (heading.matches()) {
				if (!preamble || !body.toString().isBlank()) {
					sections.add(section(title, body, keys));
				}
				preamble = false;
				title = heading.group(1).strip();
				body.setLength(0);
			}
			else {
				body.append(line).append('\n');
			}
		}
		if (!preamble || !body.toString().isBlank()) {
			sections.add(section(title, body, keys));
		}
		return List.copyOf(sections);
	}

	/** The prompt text: sections separated by a blank line, each under its {@code ## } heading. */
	public static String assemble(List<PromptSection> sections) {
		var text = new StringBuilder();
		for (PromptSection section : sections) {
			if (!text.isEmpty()) {
				text.append("\n\n");
			}
			if (section.hasHeading()) {
				text.append("## ").append(section.title().strip()).append("\n\n");
			}
			text.append(normalise(section.body()).strip());
		}
		return text.append('\n').toString();
	}

	/** {@code "2. LANGUAGE & TONE"} becomes {@code "language-tone"}. */
	static String key(String title) {
		if (title == null) {
			return PREAMBLE_KEY;
		}
		String words = NUMBERING.matcher(title.strip()).replaceFirst("").toLowerCase(Locale.ROOT);
		String key = words.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		return key.isEmpty() ? "section" : key;
	}

	/** Windows line endings and a byte-order mark would otherwise end up in the prompt. */
	static String normalise(String text) {
		String value = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
		return value.startsWith("﻿") ? value.substring(1) : value;
	}

	private static PromptSection section(String title, StringBuilder body, Set<String> keys) {
		String key = key(title);
		String unique = key;
		for (int i = 2; !keys.add(unique); i++) {
			unique = key + "-" + i;
		}
		return new PromptSection(unique, title, body.toString().strip());
	}

}
