package com.allhome.colourcoats.ingestion;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps ingestion errors to RFC 9457 problem responses with a message the operator can act on. */
@RestControllerAdvice(assignableTypes = IngestionController.class)
class IngestionExceptionHandler {

	@ExceptionHandler
	ProblemDetail invalidArchive(InvalidArchiveException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid knowledge archive", ex);
	}

	@ExceptionHandler
	ProblemDetail versionConflict(VersionConflictException ex) {
		return problem(HttpStatus.CONFLICT, "Dataset version already exists", ex);
	}

	@ExceptionHandler
	ProblemDetail runNotFound(IngestionRunNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Ingestion run not found", ex);
	}

	private static ProblemDetail problem(HttpStatus status, String title, RuntimeException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
		problem.setTitle(title);
		return problem;
	}

}
