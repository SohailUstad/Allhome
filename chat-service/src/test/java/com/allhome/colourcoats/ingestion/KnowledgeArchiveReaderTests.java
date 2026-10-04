package com.allhome.colourcoats.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeArchiveReaderTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final KnowledgeArchiveReader reader = new KnowledgeArchiveReader();

	/** Contract with the Python side: the fixture was written by ingestion/package_knowledge.py. */
	@Test
	void readsArchiveProducedByPythonPackager() throws IOException {
		try (InputStream zip = getClass().getResourceAsStream("/ingestion/python-packaged.zip")) {
			KnowledgeArchive archive = reader.read(zip);

			assertThat(archive.datasetId()).isEqualTo("colourcoats");
			assertThat(archive.datasetVersion()).isEqualTo("2026-10-03");
			assertThat(archive.source()).isEqualTo("https://www.colourcoats.com/");
			assertThat(archive.chunks()).hasSize(2);
			KnowledgeChunk first = archive.chunks().get(0);
			assertThat(first.chunkId()).isEqualTo("a1b2c3");
			assertThat(first.text()).startsWith("ColourCoats — Architectural Finishes").contains("feature walls");
			assertThat(first.headingPath()).containsExactly("Lime Wash", "Where it works");
			assertThat(first.sourceUrls()).containsExactly("https://www.colourcoats.com/#svc-limewash");
			assertThat(first.fetchedAt()).isEqualTo("2026-10-03T10:00:00+00:00");
		}
	}

	@Test
	void readsAllChunkFields() {
		KnowledgeArchive archive = read(zip(manifest(m -> {
		}), List.of(chunk("c1", c -> {
		}))));

		KnowledgeChunk chunk = archive.chunks().get(0);
		assertThat(chunk.id()).isEqualTo(KnowledgeArchiveReader.stableId("colourcoats", "v1", "c1"));
		assertThat(chunk.documentId()).isEqualTo("doc1");
		assertThat(chunk.url()).isEqualTo("https://example.com/page");
		assertThat(chunk.title()).isEqualTo("Page");
		assertThat(chunk.headingPath()).containsExactly("Section");
	}

	@Test
	void optionalFieldsMayBeMissingOrNull() {
		KnowledgeChunk chunk = read(zip(manifest(m -> {
		}), List.of(chunk("c1", c -> {
			c.remove("title");
			c.put("fetched_at", null);
			c.remove("heading_path");
			c.remove("source_urls");
		})))).chunks().get(0);

		assertThat(chunk.title()).isNull();
		assertThat(chunk.fetchedAt()).isNull();
		assertThat(chunk.headingPath()).isEmpty();
		assertThat(chunk.sourceUrls()).isEmpty();
	}

	@Test
	void idsAreStablePerVersionAndUnambiguous() {
		assertThat(KnowledgeArchiveReader.stableId("colourcoats", "v1", "c1"))
			.isEqualTo(KnowledgeArchiveReader.stableId("colourcoats", "v1", "c1"))
			.isNotEqualTo(KnowledgeArchiveReader.stableId("colourcoats", "v2", "c1"));
		// Without length prefixes these two would concatenate to the same key.
		assertThat(KnowledgeArchiveReader.stableId("ab", "c", "d"))
			.isNotEqualTo(KnowledgeArchiveReader.stableId("a", "bc", "d"));
	}

	@Test
	void acceptsByteOrderMarkAndBlankLines() {
		byte[] chunks = ("﻿" + json(chunk("c1", c -> {
		})) + "\n\n" + json(chunk("c2", c -> {
		})) + "\n").getBytes(StandardCharsets.UTF_8);
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("manifest.json", json(manifest(m -> m.put("chunk_count", 2))).getBytes(StandardCharsets.UTF_8));
		entries.put("chunks.jsonl", chunks);

		assertThat(read(zipEntries(entries)).chunks()).extracting(KnowledgeChunk::chunkId).containsExactly("c1", "c2");
	}

	@Test
	void rejectsWrongZipLayout() {
		Map<String, byte[]> extra = validEntries();
		extra.put("notes.txt", "x".getBytes(StandardCharsets.UTF_8));
		assertRejected(zipEntries(extra), "only manifest.json and chunks.jsonl");

		Map<String, byte[]> nested = new LinkedHashMap<>();
		validEntries().forEach((name, content) -> nested.put("knowledge/" + name, content));
		assertRejected(zipEntries(nested), "only manifest.json and chunks.jsonl");

		Map<String, byte[]> missing = validEntries();
		missing.remove("chunks.jsonl");
		assertRejected(zipEntries(missing), "must contain manifest.json and chunks.jsonl");

		assertRejected("not a zip".getBytes(StandardCharsets.UTF_8), "must contain manifest.json and chunks.jsonl");
	}

	@Test
	void rejectsOversizedContent() {
		Map<String, byte[]> entries = validEntries();
		entries.put("chunks.jsonl", new byte[KnowledgeArchiveReader.MAX_EXPANDED_BYTES + 1]);
		assertRejected(zipEntries(entries), "larger than 20 MB");
	}

	@Test
	void rejectsInvalidEncodingAndJson() {
		Map<String, byte[]> badUtf8 = validEntries();
		badUtf8.put("chunks.jsonl", new byte[] { (byte) 0xC3, (byte) 0x28 });
		assertRejected(zipEntries(badUtf8), "chunks.jsonl is not valid UTF-8");

		Map<String, byte[]> badJson = validEntries();
		badJson.put("chunks.jsonl", "{\"id\": ".getBytes(StandardCharsets.UTF_8));
		assertRejected(zipEntries(badJson), "Invalid JSON in chunks.jsonl line 1");

		Map<String, byte[]> duplicateKey = validEntries();
		duplicateKey.put("manifest.json", "{\"dataset_id\":\"a\",\"dataset_id\":\"b\"}".getBytes(StandardCharsets.UTF_8));
		assertRejected(zipEntries(duplicateKey), "Invalid JSON in manifest.json");

		Map<String, byte[]> notObject = validEntries();
		notObject.put("chunks.jsonl", "[1, 2]".getBytes(StandardCharsets.UTF_8));
		assertRejected(zipEntries(notObject), "chunks.jsonl line 1 must be a JSON object");
	}

	@Test
	void rejectsInvalidManifest() {
		List<Map<String, Object>> oneChunk = List.of(chunk("c1", c -> {
		}));
		assertRejected(zip(manifest(m -> m.put("schema_version", "2.0")), oneChunk), "Unsupported schema_version");
		assertRejected(zip(manifest(m -> m.put("dataset_id", "colour coats")), oneChunk), "dataset_id must be");
		assertRejected(zip(manifest(m -> m.put("dataset_version", "v".repeat(101))), oneChunk), "dataset_version is longer");
		assertRejected(zip(manifest(m -> m.put("source", "ftp://example.com")), oneChunk), "source must be an absolute http(s) URL");
		assertRejected(zip(manifest(m -> m.put("chunks_file", "other.jsonl")), oneChunk), "chunks_file");
		assertRejected(zip(manifest(m -> m.remove("dataset_id")), oneChunk), "missing or non-text field 'dataset_id'");
		assertRejected(zip(manifest(m -> m.put("chunk_count", "1")), oneChunk), "chunk_count in manifest.json");
		assertRejected(zip(manifest(m -> m.put("chunk_count", 0)), oneChunk), "chunk_count in manifest.json");
		assertRejected(zip(manifest(m -> m.put("chunk_count", KnowledgeArchiveReader.MAX_CHUNKS + 1)), oneChunk),
				"chunk_count in manifest.json");
		assertRejected(zip(manifest(m -> m.put("chunk_count", 2)), oneChunk), "chunk_count is 2 but chunks.jsonl has 1");
	}

	@Test
	void rejectsInvalidChunks() {
		assertRejected(zip(manifest(m -> m.put("chunk_count", 2)), List.of(chunk("c1", c -> {
		}), chunk("c1", c -> {
		}))), "line 2: duplicate id");
		assertRejected(oneChunk(c -> c.put("id", "x".repeat(KnowledgeArchiveReader.MAX_ID_LENGTH + 1))), "id is longer");
		assertRejected(oneChunk(c -> c.remove("document_id")), "missing or non-text field 'document_id'");
		assertRejected(oneChunk(c -> c.put("text", "   ")), "missing or non-text field 'text'");
		assertRejected(oneChunk(c -> c.put("text", 42)), "missing or non-text field 'text'");
		assertRejected(oneChunk(c -> c.put("text", "é".repeat(KnowledgeArchiveReader.MAX_CHUNK_TEXT_BYTES / 2 + 1))),
				"text is longer than 8000 UTF-8 bytes");
		assertRejected(oneChunk(c -> c.put("url", "/relative/page")), "url must be an absolute http(s) URL");
		assertRejected(oneChunk(c -> c.put("title", 7)), "field 'title' must be text");
		assertRejected(oneChunk(c -> c.put("heading_path", "Section")), "field 'heading_path' must be a list of text");
		assertRejected(oneChunk(c -> c.put("source_urls", List.of(1))), "field 'source_urls' must be a list of text");
	}

	// --- helpers ---

	private KnowledgeArchive read(byte[] zip) {
		return reader.read(new ByteArrayInputStream(zip));
	}

	private void assertRejected(byte[] zip, String messagePart) {
		assertThatThrownBy(() -> read(zip)).isInstanceOf(InvalidArchiveException.class).hasMessageContaining(messagePart);
	}

	private static byte[] oneChunk(Consumer<Map<String, Object>> change) {
		return zip(manifest(m -> {
		}), List.of(chunk("c1", change)));
	}

	private static Map<String, Object> manifest(Consumer<Map<String, Object>> change) {
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schema_version", "1.0");
		manifest.put("dataset_id", "colourcoats");
		manifest.put("dataset_version", "v1");
		manifest.put("source", "https://example.com/");
		manifest.put("chunks_file", "chunks.jsonl");
		manifest.put("chunk_count", 1);
		change.accept(manifest);
		return manifest;
	}

	private static Map<String, Object> chunk(String id, Consumer<Map<String, Object>> change) {
		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("id", id);
		chunk.put("document_id", "doc1");
		chunk.put("text", "Page\nSection\n\nSome text.");
		chunk.put("url", "https://example.com/page");
		chunk.put("title", "Page");
		chunk.put("fetched_at", "2026-10-03T10:00:00+00:00");
		chunk.put("heading_path", List.of("Section"));
		chunk.put("source_urls", List.of("https://example.com/page#section"));
		change.accept(chunk);
		return chunk;
	}

	private static Map<String, byte[]> validEntries() {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("manifest.json", json(manifest(m -> {
		})).getBytes(StandardCharsets.UTF_8));
		entries.put("chunks.jsonl", (json(chunk("c1", c -> {
		})) + "\n").getBytes(StandardCharsets.UTF_8));
		return entries;
	}

	private static byte[] zip(Map<String, Object> manifest, List<Map<String, Object>> chunks) {
		List<String> lines = new ArrayList<>();
		chunks.forEach(chunk -> lines.add(json(chunk)));
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("manifest.json", json(manifest).getBytes(StandardCharsets.UTF_8));
		entries.put("chunks.jsonl", (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
		return zipEntries(entries);
	}

	private static byte[] zipEntries(Map<String, byte[]> entries) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	private static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

}
