package com.allhome.colourcoats.ingestion;

import java.util.List;

import com.allhome.colourcoats.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A stored retrieval chunk of one ingestion run, with its embedding. */
@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunkEntity extends AssignedIdEntity {

	/** Dimensions of OpenAI text-embedding-3-small, the model used for every chunk and query. */
	public static final int EMBEDDING_DIMENSIONS = 1536;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "ingestion_run_id", nullable = false, updatable = false)
	private IngestionRun run;

	@Column(nullable = false, length = 256)
	private String chunkId;

	@Column(nullable = false, length = 256)
	private String documentId;

	@Column(nullable = false, columnDefinition = "text")
	private String text;

	@Column(nullable = false, columnDefinition = "text")
	private String url;

	@Column(columnDefinition = "text")
	private String title;

	@Column(columnDefinition = "text")
	private String fetchedAt;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(nullable = false, columnDefinition = "text[]")
	private List<String> headingPath;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(nullable = false, columnDefinition = "text[]")
	private List<String> sourceUrls;

	@JdbcTypeCode(SqlTypes.VECTOR)
	@Array(length = EMBEDDING_DIMENSIONS)
	@Column(nullable = false)
	private float[] embedding;

	protected KnowledgeChunkEntity() {
	}

	KnowledgeChunkEntity(IngestionRun run, KnowledgeChunk chunk, float[] embedding) {
		super(chunk.id());
		this.run = run;
		this.chunkId = chunk.chunkId();
		this.documentId = chunk.documentId();
		this.text = chunk.text();
		this.url = chunk.url();
		this.title = chunk.title();
		this.fetchedAt = chunk.fetchedAt();
		this.headingPath = chunk.headingPath();
		this.sourceUrls = chunk.sourceUrls();
		this.embedding = embedding;
	}

	public IngestionRun getRun() {
		return run;
	}

	public String getChunkId() {
		return chunkId;
	}

	public String getDocumentId() {
		return documentId;
	}

	public String getText() {
		return text;
	}

	public String getUrl() {
		return url;
	}

	public String getTitle() {
		return title;
	}

	public String getFetchedAt() {
		return fetchedAt;
	}

	public List<String> getHeadingPath() {
		return headingPath;
	}

	public List<String> getSourceUrls() {
		return sourceUrls;
	}

	public float[] getEmbedding() {
		return embedding;
	}

}
