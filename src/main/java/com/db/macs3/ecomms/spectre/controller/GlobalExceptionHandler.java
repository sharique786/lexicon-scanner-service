package com.db.macs3.ecomms.spectre.controller;

import com.db.macs3.ecomms.spectre.client.LexiconCompileClient.LexiconCompileException;
import com.db.macs3.ecomms.spectre.service.CsvParserService.CsvParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.Map;

/**
 * Global exception handler — converts domain exceptions to structured HTTP responses.
 *
 * <p>All error responses follow the same JSON shape:
 * <pre>{@code { "status": 4xx|5xx, "error": "message", "timestamp": "ISO-8601" }}</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Upstream Lexicon Compile Service unavailable or returned an error. */
    @ExceptionHandler(LexiconCompileException.class)
    public ResponseEntity<Map<String, Object>> handleCompileException(LexiconCompileException e) {
        log.error("Lexicon Compile Service error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(502, e.getMessage()));
    }

    /** CSV file could not be parsed. */
    @ExceptionHandler(CsvParseException.class)
    public ResponseEntity<Map<String, Object>> handleCsvParseException(CsvParseException e) {
        log.warn("CSV parse error: {}", e.getMessage());
        return ResponseEntity.badRequest().body(error(400, e.getMessage()));
    }

    /** Uploaded file exceeds configured multipart size limit. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleSizeLimit(MaxUploadSizeExceededException e) {
        log.warn("Upload too large: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error(413, "Uploaded file exceeds the maximum allowed size."));
    }

    /** Catch-all for any other uncaught exception. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        log.error("Unhandled exception in scan pipeline: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(500, "An internal error occurred: " + e.getMessage()));
    }

    private Map<String, Object> error(int status, String message) {
        return Map.of(
                "status", status,
                "error", message != null ? message : "Unknown error",
                "timestamp", Instant.now().toString()
        );
    }
}
