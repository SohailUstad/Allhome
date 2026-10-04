package com.allhome.colourcoats.chat;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * JSON error responses for the REST APIs only. Pages and static files (e.g. a missing /favicon.ico) use Spring Boot's
 * normal error handling, so a 404 stays a 404 instead of being reported as a 503 provider failure.
 */
@RestControllerAdvice(annotations = RestController.class)
public class ApiErrors {
    private com.allhome.colourcoats.flowlog.Telemetry telemetry = com.allhome.colourcoats.flowlog.Telemetry.local();
    @org.springframework.beans.factory.annotation.Autowired
    public void observability(com.allhome.colourcoats.flowlog.Telemetry telemetry) { this.telemetry = telemetry; }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidRequest(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted().collect(java.util.stream.Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableRequest(HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Provide a valid JSON request body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail invalidParameter(MethodArgumentTypeMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> notAcceptable() {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException ex) {
        return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
    }

    @ExceptionHandler(InvalidArchiveException.class)
    public ProblemDetail invalid(InvalidArchiveException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail missing(MissingServletRequestPartException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Provide the ZIP in multipart field 'file'");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail oversized(MaxUploadSizeExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds the 10 MB ZIP limit");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail failed(Exception ex) {
        // Avoid returning provider messages or database connection details to clients.
        telemetry.failure("api.operation.failed", ex);
        com.allhome.colourcoats.flowlog.TraceContext.outcome("failed");
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "AI provider or database operation failed. Check configuration and retry. Ingestion batch writes are rolled back on failure.");
    }
}
