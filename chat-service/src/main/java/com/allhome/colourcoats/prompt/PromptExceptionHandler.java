package com.allhome.colourcoats.prompt;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps prompt errors to RFC 9457 problem responses with a message the operator can act on. */
@RestControllerAdvice(assignableTypes = PromptController.class)
class PromptExceptionHandler {

	@ExceptionHandler
	ProblemDetail notFound(PromptExceptions.NotFound ex) {
		return problem(HttpStatus.NOT_FOUND, "Prompt version not found", ex);
	}

	@ExceptionHandler
	ProblemDetail confirmationRequired(PromptExceptions.ConfirmationRequired ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Confirmation required", ex);
		problem.setProperty("protectedSections", ex.sections());
		return problem;
	}

	@ExceptionHandler
	ProblemDetail ruleViolation(PromptExceptions.RuleViolation ex) {
		return problem(HttpStatus.BAD_REQUEST, "Change not allowed", ex);
	}

	@ExceptionHandler
	ProblemDetail conflict(PromptExceptions.Conflict ex) {
		return problem(HttpStatus.CONFLICT, "Version conflict", ex);
	}

	private static ProblemDetail problem(HttpStatus status, String title, RuntimeException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
		problem.setTitle(title);
		return problem;
	}

}
