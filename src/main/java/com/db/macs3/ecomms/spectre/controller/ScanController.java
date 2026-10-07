package com.db.macs3.ecomms.spectre.controller;

import com.db.macs3.ecomms.spectre.config.AppProperties;
import com.db.macs3.ecomms.spectre.model.DisclaimerSource;
import com.db.macs3.ecomms.spectre.model.MessageSource;
import com.db.macs3.ecomms.spectre.model.Models.ScanApiRequest;
import com.db.macs3.ecomms.spectre.model.Models.ScanResponse;
import com.db.macs3.ecomms.spectre.model.TermSource;
import com.db.macs3.ecomms.spectre.model.TermType;
import com.db.macs3.ecomms.spectre.service.CsvParserService;
import com.db.macs3.ecomms.spectre.service.LexiconScanOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller exposing the Lexicon Scanner API.
 *
 * <h2>Endpoints</h2>
 * <pre>
 * POST /api/scan            — multipart/form-data  (UI form submission)
 * POST /api/scan/json       — application/json     (programmatic / GZIP clients)
 * GET  /api/scan/health     — liveness check
 * </pre>
 *
 * <h2>Input modes</h2>
 * <ul>
 *   <li>{@code inputMode=TERMS} + {@code terms}   — newline-separated term descriptions</li>
 *   <li>{@code inputMode=CSV}   + {@code csvFile}  — uploaded CSV file (term_id, term_desc, risk)</li>
 * </ul>
 *
 * <h2>Term type ("Request Type")</h2>
 * <ul>
 *   <li>{@code termType=NATURAL_LANGUAGE} (default) — term(s) use the operator
 *       language and are translated via the Lexicon Compile Service.</li>
 *   <li>{@code termType=REGEX} — term(s) are already Hyperscan-compatible
 *       patterns and are compiled directly, without calling the Compile Service.</li>
 * </ul>
 *
 * <h2>Message modes</h2>
 * <ul>
 *   <li>{@code messageMode=TEXT} + {@code messageText} — one message pasted in the UI</li>
 *   <li>{@code messageMode=FILE} + {@code messageFiles} — one or more uploaded
 *       message files; EACH becomes its own scanned message, all checked
 *       against the same term set and disclaimer set in one scan session</li>
 * </ul>
 *
 * <h2>Disclaimer modes (optional — default NONE)</h2>
 * <ul>
 *   <li>{@code disclaimerInputMode=NONE} (default) — no disclaimer exclusion</li>
 *   <li>{@code disclaimerInputMode=TEXT} + {@code disclaimers} — one or more
 *       disclaimer texts, separated by a BLANK LINE (not a single newline,
 *       since one disclaimer may itself span multiple lines)</li>
 *   <li>{@code disclaimerInputMode=CSV} + {@code disclaimerCsvFile} — uploaded
 *       CSV of disclaimer texts, same two-column shape as a lexicon term CSV</li>
 * </ul>
 *
 * <p>Every request is validated before the scan pipeline runs. Validation
 * errors return HTTP 400 with a descriptive JSON body.
 */
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    private final LexiconScanOrchestrator orchestrator;
    private final CsvParserService csvParser;
    private final AppProperties props;

    public ScanController(LexiconScanOrchestrator orchestrator,
                          CsvParserService csvParser,
                          AppProperties props) {
        this.orchestrator = orchestrator;
        this.csvParser = csvParser;
        this.props = props;
    }

    /**
     * Multipart form-data endpoint — the primary entry point from the web UI.
     *
     * @param inputMode          {@code TERMS} or {@code CSV}
     * @param termType           {@code NATURAL_LANGUAGE} (default) or {@code REGEX}
     * @param messageMode        {@code TEXT} or {@code FILE}
     * @param terms              (optional) newline-separated terms when inputMode=TERMS
     * @param csvFile            (optional) CSV file when inputMode=CSV
     * @param messageText        (optional) message text when messageMode=TEXT
     * @param messageFiles       (optional) one or more message files when messageMode=FILE
     * @param disclaimerInputMode {@code NONE} (default), {@code TEXT}, or {@code CSV}
     * @param disclaimers        (optional) blank-line-separated disclaimer texts when disclaimerInputMode=TEXT
     * @param disclaimerCsvFile  (optional) CSV file when disclaimerInputMode=CSV
     * @param ruleName           (optional) name for this scan session
     * @return HTTP 200 with {@link ScanResponse}, or HTTP 400 on validation failure
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanResponse> scanMultipart(
            @RequestParam(defaultValue = "TERMS") String inputMode,
            @RequestParam(defaultValue = "NATURAL_LANGUAGE") String termType,
            @RequestParam(defaultValue = "TEXT")  String messageMode,
            @RequestParam(required = false) String terms,
            @RequestParam(required = false) MultipartFile csvFile,
            @RequestParam(required = false) String messageText,
            @RequestParam(required = false) List<MultipartFile> messageFiles,
            @RequestParam(defaultValue = "NONE") String disclaimerInputMode,
            @RequestParam(required = false) String disclaimers,
            @RequestParam(required = false) MultipartFile disclaimerCsvFile,
            @RequestParam(required = false) String ruleName) throws IOException {

        TermType resolvedTermType = TermType.fromString(termType);
        log.info("POST /api/scan (multipart): inputMode={}, termType={}, messageMode={}, disclaimerInputMode={}",
                inputMode, resolvedTermType, messageMode, disclaimerInputMode);

        // ── Validate + resolve terms ──────────────────────────────────────────
        if ("CSV".equalsIgnoreCase(inputMode) && csvFile == null) {
            return badRequest("Please upload a CSV file, or switch to \"Type Terms\" and enter at least one term.");
        }
        if ("CSV".equalsIgnoreCase(inputMode) && csvFile.getSize() == 0) {
            return badRequest("The uploaded CSV file is empty. Please upload a CSV containing at least one lexicon term.");
        }
        if (!"CSV".equalsIgnoreCase(inputMode) && (terms == null || terms.isBlank())) {
            return badRequest("Please enter at least one lexicon term, or switch to \"Upload CSV\".");
        }

        TermSource termSource = resolveTermSource(inputMode, resolvedTermType, terms, csvFile);

        if (termSource instanceof TermSource.TextTerms text) {
            if (text.descriptions().isEmpty()) {
                return badRequest("No lexicon terms provided. Enter at least one term or upload a non-empty CSV.");
            }
            if (text.descriptions().size() > props.getScanner().getMaxTerms()) {
                return badRequest("Too many terms: " + text.descriptions().size()
                        + " exceeds the limit of " + props.getScanner().getMaxTerms() + ".");
            }
        }

        // ── Validate + resolve message(s) ───────────────────────────────────────
        MessageSourceResolution messageResolution = resolveMessageSource(messageMode, messageText, messageFiles);
        if (messageResolution.errorMessage() != null) {
            return badRequest(messageResolution.errorMessage());
        }

        // ── Validate + resolve disclaimer(s) (optional) ─────────────────────────
        DisclaimerSourceResolution disclaimerResolution =
                resolveDisclaimerSource(disclaimerInputMode, disclaimers, disclaimerCsvFile);
        if (disclaimerResolution.errorMessage() != null) {
            return badRequest(disclaimerResolution.errorMessage());
        }

        ScanResponse response = orchestrator.scan(resolvedTermType, termSource,
                messageResolution.messageSource(), disclaimerResolution.disclaimerSource(), ruleName);

        // Natural-Language + CSV: the Compile Service parsed the file; if it
        // found zero terms, surface a clear, CSV-specific error rather than
        // an empty results screen.
        if ("CSV".equalsIgnoreCase(inputMode) && "SUCCESS".equals(response.status()) && response.totalTerms() == 0) {
            return badRequest("The uploaded CSV file does not contain any valid lexicon terms.");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * JSON endpoint for programmatic clients and GZIP-compressed requests.
     * Always single-message (text only) and text-only disclaimers — multi-file
     * upload and disclaimer CSV forwarding require a real file upload, so
     * they are multipart-only, same reasoning as the existing CSV-forwarding note below.
     *
     * @param request scan request body
     * @return HTTP 200 with {@link ScanResponse}
     */
    @PostMapping(path = "/json",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScanResponse> scanJson(@RequestBody ScanApiRequest request) {
        TermType resolvedTermType = TermType.fromString(request.termType());
        log.info("POST /api/scan/json: inputMode={}, termType={}, messageMode={}",
                request.inputMode(), resolvedTermType, request.messageMode());

        List<String> termList = resolveTermsFromJson(request);
        if (termList.isEmpty()) {
            return badRequest("No lexicon terms provided in the JSON request body.");
        }

        String message = "FILE".equalsIgnoreCase(request.messageMode())
                ? "" // JSON callers must provide text, not a file
                : (request.messageText() != null ? request.messageText().strip() : "");
        if (message.isBlank()) {
            return badRequest("messageText is required in the JSON request body.");
        }

        DisclaimerSource disclaimerSource = "TEXT".equalsIgnoreCase(request.disclaimerInputMode())
                ? new DisclaimerSource.TextDisclaimers(splitDisclaimers(request.disclaimers()))
                : new DisclaimerSource.None();

        ScanResponse response = orchestrator.scan(
                resolvedTermType, new TermSource.TextTerms(termList),
                new MessageSource.SingleText(message), disclaimerSource, request.ruleName());
        return ResponseEntity.ok(response);
    }

    /**
     * Health check — returns service status and key configuration values.
     * Used by GKE/Compute Engine liveness probes and the Lexicon Scanner UI header.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "lexicon-scanner-service",
                "compileServiceUrl", props.getCompileService().getBaseUrl(),
                "maxTerms", props.getScanner().getMaxTerms()
        ));
    }

    // ── Term resolution helpers ──────────────────────────────────────────────────

    private TermSource resolveTermSource(String inputMode, TermType termType,
                                          String terms, MultipartFile csvFile) {
        if ("CSV".equalsIgnoreCase(inputMode)) {
            if (termType == TermType.REGEX) {
                List<String> parsed = csvParser.parseTermDescriptions(csvFile);
                return new TermSource.TextTerms(parsed);
            }
            return new TermSource.CsvFile(csvFile);
        }
        return new TermSource.TextTerms(splitLines(terms));
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[\r\n]+"))
                .map(String::strip)
                .filter(s -> !s.isBlank() && !s.startsWith("#"))
                .collect(Collectors.toList());
    }

    private List<String> resolveTermsFromJson(ScanApiRequest request) {
        if ("CSV".equalsIgnoreCase(request.inputMode()) && request.csvContent() != null) {
            return csvParser.parseTermDescriptionsFromText(request.csvContent());
        }
        return splitLines(request.terms());
    }

    // ── Message resolution + validation ──────────────────────────────────────────

    private record MessageSourceResolution(MessageSource messageSource, String errorMessage) {
        static MessageSourceResolution of(MessageSource source) { return new MessageSourceResolution(source, null); }
        static MessageSourceResolution error(String message) { return new MessageSourceResolution(null, message); }
    }

    /**
     * Resolves + validates the message(s) to scan. For {@code messageMode=FILE},
     * EVERY uploaded file is read and length-checked here (not in the
     * orchestrator — see {@link MessageSource} class Javadoc) so a single
     * oversized file produces a clear, specific error naming that file
     * rather than a generic pipeline failure partway through the scan.
     */
    private MessageSourceResolution resolveMessageSource(String messageMode, String messageText,
                                                           List<MultipartFile> messageFiles) throws IOException {
        if ("FILE".equalsIgnoreCase(messageMode)) {
            List<MultipartFile> files = messageFiles == null ? List.of()
                    : messageFiles.stream().filter(f -> f != null && !f.isEmpty()).toList();
            if (files.isEmpty()) {
                return MessageSourceResolution.error(
                        "No message file provided. Upload one or more message files, or switch to \"Type / Paste\".");
            }

            List<MessageSource.NamedMessageText> resolved = new ArrayList<>(files.size());
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String text = new String(file.getBytes(), StandardCharsets.UTF_8).strip();
                String name = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                        ? file.getOriginalFilename() : "Uploaded File " + (i + 1);

                if (text.isBlank()) {
                    return MessageSourceResolution.error("File \"" + name + "\" is empty. Every uploaded message file must contain text.");
                }
                if (text.length() > props.getScanner().getMaxMessageLength()) {
                    return MessageSourceResolution.error("File \"" + name + "\" is too long: " + text.length()
                            + " chars exceeds the limit of " + props.getScanner().getMaxMessageLength() + ".");
                }
                resolved.add(new MessageSource.NamedMessageText(name, text));
            }
            return MessageSourceResolution.of(new MessageSource.MultipleMessages(resolved));
        }

        String message = (messageText != null) ? messageText.strip() : "";
        if (message.isBlank()) {
            return MessageSourceResolution.error("No message text provided. Enter message text or upload one or more message files.");
        }
        if (message.length() > props.getScanner().getMaxMessageLength()) {
            return MessageSourceResolution.error("Message too long: " + message.length()
                    + " chars exceeds limit of " + props.getScanner().getMaxMessageLength() + ".");
        }
        return MessageSourceResolution.of(new MessageSource.SingleText(message));
    }

    // ── Disclaimer resolution + validation ────────────────────────────────────────

    private record DisclaimerSourceResolution(DisclaimerSource disclaimerSource, String errorMessage) {
        static DisclaimerSourceResolution of(DisclaimerSource source) { return new DisclaimerSourceResolution(source, null); }
        static DisclaimerSourceResolution error(String message) { return new DisclaimerSourceResolution(null, message); }
    }

    private DisclaimerSourceResolution resolveDisclaimerSource(String disclaimerInputMode, String disclaimers,
                                                                 MultipartFile disclaimerCsvFile) {
        if ("CSV".equalsIgnoreCase(disclaimerInputMode)) {
            if (disclaimerCsvFile == null || disclaimerCsvFile.isEmpty()) {
                return DisclaimerSourceResolution.error(
                        "Please upload a disclaimer CSV file, or switch to \"Type Disclaimer(s)\".");
            }
            if (disclaimerCsvFile.getSize() == 0) {
                return DisclaimerSourceResolution.error("The uploaded disclaimer CSV file is empty.");
            }
            return DisclaimerSourceResolution.of(new DisclaimerSource.CsvFile(disclaimerCsvFile));
        }
        if ("TEXT".equalsIgnoreCase(disclaimerInputMode)) {
            List<String> parsed = splitDisclaimers(disclaimers);
            if (parsed.isEmpty()) {
                return DisclaimerSourceResolution.error(
                        "Disclaimer exclusion is enabled but no disclaimer text was entered.");
            }
            return DisclaimerSourceResolution.of(new DisclaimerSource.TextDisclaimers(parsed));
        }
        return DisclaimerSourceResolution.of(new DisclaimerSource.None());
    }

    /**
     * Splits disclaimer text on BLANK LINES (one or more consecutive newlines
     * with only whitespace between), not single newlines — unlike a lexicon
     * term, a single disclaimer commonly spans several lines/paragraphs of
     * boilerplate, and splitting on every newline would fragment one
     * disclaimer into several impossible-to-match single-line pieces.
     * Separate multiple distinct disclaimers with one blank line between them.
     */
    private List<String> splitDisclaimers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("(\\r?\\n\\s*){2,}"))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private ResponseEntity<ScanResponse> badRequest(String errorMessage) {
        log.warn("Bad request: {}", errorMessage);
        return ResponseEntity
                .badRequest()
                .body(ScanResponse.error("validation-error", errorMessage));
    }
}
