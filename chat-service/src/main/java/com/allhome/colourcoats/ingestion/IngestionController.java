package com.allhome.colourcoats.ingestion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Operator API for loading knowledge archives and choosing the active version. */
@RestController
@RequestMapping("/api/ingestions")
class IngestionController {

	private final KnowledgeArchiveReader reader;

	private final IngestionService service;

	IngestionController(KnowledgeArchiveReader reader, IngestionService service) {
		this.reader = reader;
		this.service = service;
	}

	/** 201 when a new version was stored, 200 when the same version and content was already stored. */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<IngestionResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new InvalidArchiveException("Upload a non-empty ZIP file in the 'file' field");
		}
		byte[] zip = file.getBytes();
		KnowledgeArchive archive = reader.read(new ByteArrayInputStream(zip));
		IngestionResult result = service.ingest(archive, sha256Hex(zip));
		HttpStatus status = result.status() == IngestionResult.Status.INGESTED ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(result);
	}

	@GetMapping
	List<IngestionRunSummary> list(@RequestParam(required = false) String datasetId) {
		return service.list(datasetId);
	}

	@PostMapping("/{runId}/activate")
	IngestionResult activate(@PathVariable UUID runId) {
		return service.activate(runId);
	}

	private static String sha256Hex(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
		}
	}

}
