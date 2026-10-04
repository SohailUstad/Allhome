package com.allhome.colourcoats.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and validates a knowledge archive: a ZIP holding exactly {@code manifest.json} and {@code chunks.jsonl} at its
 * root, as written by {@code ingestion/package_knowledge.py}. The whole archive is checked before anything is returned,
 * so an invalid upload never stores partial data.
 */
@Component
public class KnowledgeArchiveReader {

	static final String MANIFEST = "manifest.json";

	static final String CHUNKS = "chunks.jsonl";

	static final int MAX_EXPANDED_BYTES = 20 * 1024 * 1024;

	static final int MAX_CHUNKS = 10_000;

	static final int MAX_CHUNK_TEXT_BYTES = 8_000;

	static final int MAX_ID_LENGTH = 256;

	static final int MAX_VERSION_LENGTH = 100;

	private static final String SCHEMA_VERSION = "1.0";

	private static final Pattern DATASET_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

	private final JsonMapper json = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	/**
	 * @throws InvalidArchiveException if the archive is not a valid knowledge archive; the message says what is wrong
	 */
	public KnowledgeArchive read(InputStream zip) {
		Map<String, byte[]> files = unzip(zip);

		JsonNode manifest = parseObject(decode(files.get(MANIFEST), MANIFEST), MANIFEST);
		if (!SCHEMA_VERSION.equals(requiredText(manifest, "schema_version", MANIFEST))) {
			throw invalid("Unsupported schema_version in manifest.json (expected " + SCHEMA_VERSION + ")");
		}
		String datasetId = requiredText(manifest, "dataset_id", MANIFEST);
		if (!DATASET_ID.matcher(datasetId).matches()) {
			throw invalid("dataset_id must be 1-100 letters, digits, '-' or '_'");
		}
		String datasetVersion = requiredText(manifest, "dataset_version", MANIFEST);
		if (datasetVersion.length() > MAX_VERSION_LENGTH) {
			throw invalid("dataset_version is longer than " + MAX_VERSION_LENGTH + " characters");
		}
		String source = requiredText(manifest, "source", MANIFEST);
		requireHttpUrl(source, "manifest.json source");
		if (!CHUNKS.equals(requiredText(manifest, "chunks_file", MANIFEST))) {
			throw invalid("chunks_file in manifest.json must be " + CHUNKS);
		}
		int expectedChunks = chunkCount(manifest);

		List<KnowledgeChunk> chunks = parseChunks(decode(files.get(CHUNKS), CHUNKS), datasetId, datasetVersion);
		if (chunks.size() != expectedChunks) {
			throw invalid("chunk_count is %d but chunks.jsonl has %d chunks".formatted(expectedChunks, chunks.size()));
		}
		return new KnowledgeArchive(datasetId, datasetVersion, source, chunks);
	}

	/**
	 * Stable per dataset, version and chunk, so re-reading the same archive yields the same ids while different
	 * versions never collide. Length prefixes keep the parts unambiguous.
	 */
	static UUID stableId(String datasetId, String datasetVersion, String chunkId) {
		String key = datasetId.length() + ":" + datasetId + datasetVersion.length() + ":" + datasetVersion + chunkId;
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, byte[]> unzip(InputStream input) {
		Map<String, byte[]> files = new HashMap<>();
		long expanded = 0;
		try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				String name = entry.getName();
				if (entry.isDirectory() || !(MANIFEST.equals(name) || CHUNKS.equals(name))) {
					throw invalid("ZIP must contain only manifest.json and chunks.jsonl at its root, found: "
							+ abbreviate(name));
				}
				if (files.containsKey(name)) {
					throw invalid("Duplicate ZIP entry: " + name);
				}
				// Read one byte past the remaining budget to detect oversized (or zip-bomb) content.
				byte[] content = zip.readNBytes((int) (MAX_EXPANDED_BYTES - expanded + 1));
				expanded += content.length;
				if (expanded > MAX_EXPANDED_BYTES) {
					throw invalid("Archive content is larger than " + MAX_EXPANDED_BYTES / (1024 * 1024) + " MB");
				}
				files.put(name, content);
			}
		}
		catch (ZipException ex) {
			throw invalid("Not a valid ZIP file");
		}
		catch (IOException ex) {
			throw invalid("Could not read the ZIP file");
		}
		if (!files.containsKey(MANIFEST) || !files.containsKey(CHUNKS)) {
			throw invalid("ZIP must contain manifest.json and chunks.jsonl");
		}
		return files;
	}

	private List<KnowledgeChunk> parseChunks(String content, String datasetId, String datasetVersion) {
		List<KnowledgeChunk> chunks = new ArrayList<>();
		Set<String> chunkIds = new HashSet<>();
		String[] lines = content.split("\\R");
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].isBlank()) {
				continue;
			}
			String where = "chunks.jsonl line " + (i + 1);
			if (chunks.size() == MAX_CHUNKS) {
				throw invalid("chunks.jsonl has more than " + MAX_CHUNKS + " chunks");
			}
			JsonNode node = parseObject(lines[i], where);

			String chunkId = requiredText(node, "id", where);
			requireMaxLength(chunkId, "id", where);
			if (!chunkIds.add(chunkId)) {
				throw invalid(where + ": duplicate id");
			}
			String documentId = requiredText(node, "document_id", where);
			requireMaxLength(documentId, "document_id", where);
			String text = requiredText(node, "text", where);
			if (text.getBytes(StandardCharsets.UTF_8).length > MAX_CHUNK_TEXT_BYTES) {
				throw invalid(where + ": text is longer than " + MAX_CHUNK_TEXT_BYTES + " UTF-8 bytes");
			}
			String url = requiredText(node, "url", where);
			requireHttpUrl(url, where + " url");

			chunks.add(new KnowledgeChunk(stableId(datasetId, datasetVersion, chunkId), chunkId, documentId, text, url,
					optionalText(node, "title", where), optionalText(node, "fetched_at", where),
					optionalTextList(node, "heading_path", where), optionalTextList(node, "source_urls", where)));
		}
		return chunks;
	}

	private static int chunkCount(JsonNode manifest) {
		JsonNode count = manifest.get("chunk_count");
		if (count == null || !count.isIntegralNumber() || !count.canConvertToInt() || count.asInt() < 1
				|| count.asInt() > MAX_CHUNKS) {
			throw invalid("chunk_count in manifest.json must be a whole number from 1 to " + MAX_CHUNKS);
		}
		return count.asInt();
	}

	private JsonNode parseObject(String content, String where) {
		JsonNode node;
		try {
			node = json.readTree(content);
		}
		catch (JacksonException ex) {
			throw invalid("Invalid JSON in " + where);
		}
		if (node == null || !node.isObject()) {
			throw invalid(where + " must be a JSON object");
		}
		return node;
	}

	private static String decode(byte[] bytes, String fileName) {
		try {
			String text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
			return text.startsWith("﻿") ? text.substring(1) : text;
		}
		catch (CharacterCodingException ex) {
			throw invalid(fileName + " is not valid UTF-8");
		}
	}

	private static String requiredText(JsonNode node, String field, String where) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString() || value.asString().isBlank()) {
			throw invalid(where + ": missing or non-text field '" + field + "'");
		}
		return value.asString();
	}

	private static String optionalText(JsonNode node, String field, String where) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isString()) {
			throw invalid(where + ": field '" + field + "' must be text");
		}
		return value.asString();
	}

	private static List<String> optionalTextList(JsonNode node, String field, String where) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return List.of();
		}
		if (!value.isArray()) {
			throw invalid(where + ": field '" + field + "' must be a list of text");
		}
		List<String> items = new ArrayList<>();
		for (JsonNode item : value) {
			if (!item.isString()) {
				throw invalid(where + ": field '" + field + "' must be a list of text");
			}
			items.add(item.asString());
		}
		return items;
	}

	private static void requireMaxLength(String value, String field, String where) {
		if (value.length() > MAX_ID_LENGTH) {
			throw invalid(where + ": " + field + " is longer than " + MAX_ID_LENGTH + " characters");
		}
	}

	private static void requireHttpUrl(String value, String what) {
		try {
			URI uri = URI.create(value);
			if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null) {
				throw invalid(what + " must be an absolute http(s) URL");
			}
		}
		catch (IllegalArgumentException ex) {
			throw invalid(what + " must be an absolute http(s) URL");
		}
	}

	private static String abbreviate(String value) {
		return value.length() <= 100 ? value : value.substring(0, 100) + "...";
	}

	private static InvalidArchiveException invalid(String message) {
		return new InvalidArchiveException(message);
	}

}
