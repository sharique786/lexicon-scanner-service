package com.db.macs3.ecomms.spectre.client;

import com.db.macs3.ecomms.spectre.config.AppProperties;
import com.db.macs3.ecomms.spectre.model.Models.CompileServiceRequest;
import com.db.macs3.ecomms.spectre.model.Models.CompileServiceResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * HTTP client for the Lexicon Compile Service REST API.
 *
 * <p>Wraps Spring's {@link RestClient} (Spring 6 / Boot 4) to provide a
 * typed, testable interface. All network errors and HTTP error statuses are
 * converted into {@link LexiconCompileException} so callers don't need to
 * handle raw HTTP machinery.
 *
 * <p>Endpoint called: {@code POST /api/lexicon/compile}.
 */
@Component
public class LexiconCompileClient {

    private static final Logger log = LoggerFactory.getLogger(LexiconCompileClient.class);

    private static final JsonMapper ERROR_MAPPER = JsonMapper.builder().build();

    private static final String COMPILE_PATH = "/api/lexicon/compile";
    private static final String COMPILE_CSV_PATH = "/api/lexicon/compile/csv";

    private final RestClient restClient;
    private final AppProperties props;

    public LexiconCompileClient(RestClient lexiconCompileRestClient, AppProperties props) {
        this.restClient = lexiconCompileRestClient;
        this.props = props;
    }

    /**
     * Sends a batch of raw term descriptions to the Lexicon Compile Service and
     * returns the compilation results (pattern + flags per term).
     *
     * @param termDescriptions list of raw lexicon operator expressions,
     *                         e.g. {@code ["insider AND trading", "pump OR dump"]}
     * @param ruleName         logical name for this compilation session; defaults to
     *                         the configured {@code lexicon.compile-service.default-rule-name}
     *                         when blank
     * @return the compile service response containing a {@link CompileServiceResponse.TermResult}
     *         per submitted term
     * @throws LexiconCompileException if the service is unreachable, returns an HTTP error,
     *                                  or the response cannot be parsed
     */
    public CompileServiceResponse compile(List<String> termDescriptions, String ruleName) {
        if (termDescriptions == null || termDescriptions.isEmpty()) {
            log.warn("compile() called with empty term list — returning empty response");
            return emptyResponse(ruleName);
        }

        String effectiveRule = (ruleName != null && !ruleName.isBlank())
                ? ruleName
                : props.getCompileService().getDefaultRuleName();

        // Build TermInput records: termId is auto-generated as rule::N
        List<CompileServiceRequest.TermInput> terms = buildTermInputs(termDescriptions, effectiveRule);
        String requestId = java.util.UUID.randomUUID().toString();
        CompileServiceRequest request = new CompileServiceRequest(
                requestId, effectiveRule, CompileServiceRequest.TERM_TYPE_NATURAL_LANGUAGE, terms);

        log.info("Calling Lexicon Compile Service: {} terms, rule={}", terms.size(), effectiveRule);
        log.debug("Compile request terms: {}", termDescriptions);

        try {
            CompileServiceResponse response = restClient.post()
                    .uri(COMPILE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw rejection("Lexicon Compile Service client error", resp);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.error("Compile service returned 5xx: {}", resp.getStatusCode());
                        throw new LexiconCompileException(
                                "Lexicon Compile Service server error: " + resp.getStatusCode());
                    })
                    .body(CompileServiceResponse.class);

            if (response == null) {
                throw new LexiconCompileException("Lexicon Compile Service returned an empty body");
            }

            log.info("Compile service response: {} pass, {} failed (of {} total)",
                    response.passCount(), response.failedCount(), response.totalTerms());
            return response;

        } catch (ResourceAccessException e) {
            log.error("Cannot reach Lexicon Compile Service at {}: {}",
                    props.getCompileService().getBaseUrl(), e.getMessage());
            throw new LexiconCompileException(
                    "Lexicon Compile Service is unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * Forwards an uploaded CSV file AS-IS to the Lexicon Compile Service's
     * {@code POST /api/lexicon/compile/csv} endpoint, rather than parsing it
     * locally and using the JSON {@code /compile} endpoint.
     *
     * <p>Used exclusively for {@code TermType.NATURAL_LANGUAGE} + CSV input:
     * the Compile Service performs both the CSV parsing (2-column
     * {@code Term ID, Term Description} format; a third legacy column is
     * tolerated and ignored) AND the operator-language translation in one
     * round trip, guaranteeing the scanner and the Compile Service never
     * disagree about how a given CSV's rows were parsed.
     *
     * <p>{@code TermType.REGEX} CSV uploads do NOT use this method — those
     * are parsed locally by {@code CsvParserService} and compiled directly
     * into Hyperscan, bypassing the Compile Service entirely.
     *
     * @param csvFile  the uploaded CSV file, forwarded byte-for-byte
     * @param ruleName logical name for this compilation session; defaults to
     *                 the configured default rule name when blank
     * @return the compile service response — same shape as {@link #compile}
     * @throws LexiconCompileException if the service is unreachable, returns
     *                                  an HTTP error, the response cannot be
     *                                  parsed, or the uploaded file cannot be read
     */
    public CompileServiceResponse compileCsv(MultipartFile csvFile, String ruleName) {
        if (csvFile == null || csvFile.isEmpty()) {
            log.warn("compileCsv() called with a null or empty file — returning empty response");
            return emptyResponse(ruleName);
        }

        String effectiveRule = (ruleName != null && !ruleName.isBlank())
                ? ruleName
                : props.getCompileService().getDefaultRuleName();

        log.info("Forwarding CSV to Lexicon Compile Service {}: file={}, size={} bytes, rule={}",
                COMPILE_CSV_PATH, csvFile.getOriginalFilename(), csvFile.getSize(), effectiveRule);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        try {
            String filename = csvFile.getOriginalFilename() != null
                    ? csvFile.getOriginalFilename() : "terms.csv";
            ByteArrayResource fileResource = new ByteArrayResource(csvFile.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            bodyBuilder.part("file", fileResource);
            bodyBuilder.part("ruleName", effectiveRule);
        } catch (IOException e) {
            throw new LexiconCompileException("Failed to read uploaded CSV file: " + e.getMessage(), e);
        }

        try {
            CompileServiceResponse response = restClient.post()
                    .uri(COMPILE_CSV_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(bodyBuilder.build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw rejection("Lexicon Compile Service rejected the CSV file", resp);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        log.error("Compile service (CSV) returned 5xx: {}", resp.getStatusCode());
                        throw new LexiconCompileException(
                                "Lexicon Compile Service server error: " + resp.getStatusCode());
                    })
                    .body(CompileServiceResponse.class);

            if (response == null) {
                throw new LexiconCompileException("Lexicon Compile Service returned an empty body for CSV upload");
            }

            log.info("Compile service CSV response: {} pass, {} failed (of {} total)",
                    response.passCount(), response.failedCount(), response.totalTerms());
            return response;

        } catch (ResourceAccessException e) {
            log.error("Cannot reach Lexicon Compile Service at {}: {}",
                    props.getCompileService().getBaseUrl(), e.getMessage());
            throw new LexiconCompileException(
                    "Lexicon Compile Service is unreachable: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<CompileServiceRequest.TermInput> buildTermInputs(
            List<String> descriptions, String ruleName) {

        int[] counter = {1};
        return descriptions.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(desc -> new CompileServiceRequest.TermInput(
                        ruleName + "::" + counter[0]++,
                        desc.strip()))
                .toList();
    }

    /**
     * Builds the exception for a 4xx reply, carrying the Compile Service's own
     * error body — {@code {status, error, details[], timestamp}}, where a bean-validation
     * failure lists {@code "field: message"} entries in {@code details} (e.g.
     * {@code "terms[0].termDescription: termDescription must not be blank"}). An
     * unreadable or non-JSON body degrades to the bare status code.
     */
    private static LexiconCompileException rejection(String prefix, ClientHttpResponse resp) {
        int status;
        try {
            status = resp.getStatusCode().value();
        } catch (IOException e) {
            status = 400;
        }
        String error = null;
        List<String> details = List.of();
        try {
            CompileServiceError body = ERROR_MAPPER.readValue(resp.getBody(), CompileServiceError.class);
            if (body != null) {
                error = body.error();
                details = body.details() != null ? body.details() : List.of();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Compile service {} error body was not parseable: {}", status, e.getMessage());
        }
        StringBuilder message = new StringBuilder(prefix).append(": ").append(status);
        if (error != null && !error.isBlank()) {
            message.append(" — ").append(error);
        }
        if (!details.isEmpty()) {
            message.append(" (").append(String.join("; ", details)).append(')');
        }
        log.error("Compile service returned {}: {} {}", status, error, details);
        return new LexiconCompileException(message.toString(), status, details);
    }

    /** The Compile Service's JSON error body (its {@code GlobalExceptionHandler} shape). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompileServiceError(Integer status, String error, List<String> details) {}

    private static CompileServiceResponse emptyResponse(String ruleName) {
        return new CompileServiceResponse(null, ruleName, 0, 0, 0, false, List.of());
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /**
     * Unchecked exception thrown when communication with the Lexicon Compile Service fails.
     * {@link #upstreamStatus()} is the Compile Service's HTTP status when it answered
     * (0 when it could not be reached or answered 5xx without detail), and
     * {@link #details()} its per-field validation messages, if any.
     */
    public static final class LexiconCompileException extends RuntimeException {

        private final int upstreamStatus;
        private final List<String> details;

        public LexiconCompileException(String message) {
            this(message, null);
        }

        public LexiconCompileException(String message, Throwable cause) {
            super(message, cause);
            this.upstreamStatus = 0;
            this.details = List.of();
        }

        public LexiconCompileException(String message, int upstreamStatus, List<String> details) {
            super(message);
            this.upstreamStatus = upstreamStatus;
            this.details = details != null ? List.copyOf(details) : List.of();
        }

        public int upstreamStatus() {
            return upstreamStatus;
        }

        public List<String> details() {
            return details;
        }

        /** True when the Compile Service rejected the request itself (HTTP 4xx), i.e. the input was invalid. */
        public boolean isValidationFailure() {
            return upstreamStatus >= 400 && upstreamStatus < 500;
        }
    }
}
