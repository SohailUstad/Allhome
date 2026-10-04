package com.allhome.colourcoats.ingestion;

/** The uploaded knowledge archive is malformed; the message is safe to show to the uploader. */
public class InvalidArchiveException extends RuntimeException {

	public InvalidArchiveException(String message) {
		super(message);
	}

}
