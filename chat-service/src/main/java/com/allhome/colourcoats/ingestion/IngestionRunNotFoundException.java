package com.allhome.colourcoats.ingestion;

import java.util.UUID;

public class IngestionRunNotFoundException extends RuntimeException {

	public IngestionRunNotFoundException(UUID runId) {
		super("Ingestion run " + runId + " does not exist");
	}

}
