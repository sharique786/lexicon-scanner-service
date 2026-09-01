package com.db.macs3.ecomms.spectre.controller;

import com.db.macs3.ecomms.spectre.model.Models.MessageScanResult;
import com.db.macs3.ecomms.spectre.model.Models.ScanResponse;
import com.db.macs3.ecomms.spectre.model.Models.TermScanResult;
import com.db.macs3.ecomms.spectre.model.DisclaimerSource;
import com.db.macs3.ecomms.spectre.model.MessageSource;
import com.db.macs3.ecomms.spectre.model.TermSource;
import com.db.macs3.ecomms.spectre.model.TermType;
import com.db.macs3.ecomms.spectre.service.CsvParserService;
import com.db.macs3.ecomms.spectre.service.LexiconScanOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice tests for {@link ScanController}.
 *
 * <p>Uses Spring's {@link WebMvcTest} to load only the web layer; collaborating
 * services are replaced with Mockito mocks.
 */
@WebMvcTest(ScanController.class)
@DisplayName("ScanController")
class ScanControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private LexiconScanOrchestrator orchestrator;
    @MockitoBean private CsvParserService csvParser;

    // ── GET /api/scan/health ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/scan/health returns 200 with status=UP")
    void health_returns200() throws Exception {
        mvc.perform(get("/api/scan/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("UP"))
           .andExpect(jsonPath("$.service").value("lexicon-scanner-service"));
    }

    // ── POST /api/scan (multipart) — term type defaulting and routing ──────────

    @Test
    @DisplayName("POST /api/scan with no termType supplied defaults to NATURAL_LANGUAGE")
    void scanMultipart_noTermTypeSupplied_defaultsToNaturalLanguage() throws Exception {
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), anyString()))
                .thenReturn(successResponse(1, 1));

        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("messageMode", "TEXT")
                .param("terms", "insider AND trading")
                .param("messageText", "The insider leaked information.")
                .param("ruleName", "my-test"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(orchestrator).scan(
                eq(TermType.NATURAL_LANGUAGE),
                eq(new TermSource.TextTerms(List.of("insider AND trading"))),
                eq(new MessageSource.SingleText("The insider leaked information.")),
                any(DisclaimerSource.class),
                eq("my-test"));
    }

    @Test
    @DisplayName("POST /api/scan with termType=REGEX routes as TermType.REGEX")
    void scanMultipart_termTypeRegex_routesAsRegex() throws Exception {
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), anyString()))
                .thenReturn(successResponse(1, 1));

        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("termType", "REGEX")
                .param("messageMode", "TEXT")
                .param("terms", "(?:insider|trading)")
                .param("messageText", "insider trading"))
           .andExpect(status().isOk());

        verify(orchestrator).scan(eq(TermType.REGEX), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any());
    }

    @Test
    @DisplayName("POST /api/scan with an unrecognised termType falls back to NATURAL_LANGUAGE")
    void scanMultipart_unrecognisedTermType_fallsBackToNaturalLanguage() throws Exception {
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), anyString()))
                .thenReturn(successResponse(1, 1));

        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("termType", "not-a-real-type")
                .param("messageMode", "TEXT")
                .param("terms", "insider AND trading")
                .param("messageText", "some message"))
           .andExpect(status().isOk());

        verify(orchestrator).scan(eq(TermType.NATURAL_LANGUAGE), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any());
    }

    // ── POST /api/scan (multipart) — CSV routing by term type ──────────────────

    @Test
    @DisplayName("POST /api/scan CSV + NATURAL_LANGUAGE forwards the raw file (CsvFile source), never parses locally")
    void scanMultipart_csvNaturalLanguage_forwardsRawFile() throws Exception {
        String csvContent = "term_id,term_description\nt::1,pump OR dump\n";
        MockMultipartFile csvFile = new MockMultipartFile("csvFile", "terms.csv",
                "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(1, 0));

        mvc.perform(multipart("/api/scan")
                .file(csvFile)
                .param("inputMode", "CSV")
                .param("messageMode", "TEXT")
                .param("messageText", "buying and selling on news"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(orchestrator).scan(eq(TermType.NATURAL_LANGUAGE),
                org.mockito.ArgumentMatchers.argThat(src -> src instanceof TermSource.CsvFile),
                any(MessageSource.class), any(DisclaimerSource.class), any());
        verify(csvParser, org.mockito.Mockito.never()).parseTermDescriptions(any());
    }

    @Test
    @DisplayName("POST /api/scan CSV + REGEX parses the file locally (TextTerms source)")
    void scanMultipart_csvRegex_parsesLocally() throws Exception {
        String csvContent = "term_id,term_description\nt::1,(?:pump|dump)\n";
        MockMultipartFile csvFile = new MockMultipartFile("csvFile", "terms.csv",
                "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        when(csvParser.parseTermDescriptions(any())).thenReturn(List.of("(?:pump|dump)"));
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(1, 0));

        mvc.perform(multipart("/api/scan")
                .file(csvFile)
                .param("inputMode", "CSV")
                .param("termType", "REGEX")
                .param("messageMode", "TEXT")
                .param("messageText", "buying and selling on news"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(orchestrator).scan(eq(TermType.REGEX),
                eq(new TermSource.TextTerms(List.of("(?:pump|dump)"))),
                any(MessageSource.class), any(DisclaimerSource.class), any());
    }

    @Test
    @DisplayName("POST /api/scan with message file returns 200")
    void scanMultipart_messageFile_returns200() throws Exception {
        MockMultipartFile msgFile = new MockMultipartFile("messageFiles", "email.txt",
                "text/plain", "insider trading scheme".getBytes(StandardCharsets.UTF_8));

        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(1, 1));

        mvc.perform(multipart("/api/scan")
                .file(msgFile)
                .param("inputMode", "TERMS")
                .param("messageMode", "FILE")
                .param("terms", "insider AND trading"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ── Validation: requirement 3.1.1 / 3.1.2 ───────────────────────────────────

    @Test
    @DisplayName("3.1.1: POST /api/scan with no terms returns 400 with error response")
    void scanMultipart_noTerms_returns400() throws Exception {
        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("messageMode", "TEXT")
                .param("terms", "   ")
                .param("messageText", "some message"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value("ERROR"))
           .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    @Test
    @DisplayName("3.1.1: POST /api/scan with no message returns 400")
    void scanMultipart_noMessage_returns400() throws Exception {
        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("messageMode", "TEXT")
                .param("terms", "insider AND trading")
                .param("messageText", ""))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value("ERROR"));
    }

    @Test
    @DisplayName("3.1.1: POST /api/scan with no CSV file (inputMode=CSV) returns 400")
    void scanMultipart_csvModeNoFile_returns400() throws Exception {
        mvc.perform(multipart("/api/scan")
                .param("inputMode", "CSV")
                .param("messageMode", "TEXT")
                .param("messageText", "some message"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value("ERROR"));
    }

    @Test
    @DisplayName("3.1.2: POST /api/scan with an empty (0-byte) CSV file returns 400 with a clear error message")
    void scanMultipart_emptyCsvFile_returns400WithClearError() throws Exception {
        MockMultipartFile emptyCsv = new MockMultipartFile("csvFile", "empty.csv", "text/csv", new byte[0]);

        mvc.perform(multipart("/api/scan")
                .file(emptyCsv)
                .param("inputMode", "CSV")
                .param("messageMode", "TEXT")
                .param("messageText", "some message"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value("ERROR"))
           .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("empty")));
    }

    @Test
    @DisplayName("3.1.2: NATURAL_LANGUAGE + CSV forwarded to Compile Service resolving to zero terms returns 400")
    void scanMultipart_csvForwardedZeroTerms_returns400() throws Exception {
        MockMultipartFile csvFile = new MockMultipartFile("csvFile", "terms.csv",
                "text/csv", "term_id,term_description\n".getBytes(StandardCharsets.UTF_8)); // header only

        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(0, 0)); // Compile Service found zero usable rows

        mvc.perform(multipart("/api/scan")
                .file(csvFile)
                .param("inputMode", "CSV")
                .param("messageMode", "TEXT")
                .param("messageText", "some message"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value("ERROR"))
           .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("does not contain any valid")));
    }

    @Test
    @DisplayName("POST /api/scan with comment-only terms returns 400")
    void scanMultipart_commentOnlyTerms_returns400() throws Exception {
        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("messageMode", "TEXT")
                .param("terms", "# this is a comment\n# another comment")
                .param("messageText", "some message"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/scan with multiple terms (newline-separated) passes all to orchestrator")
    void scanMultipart_multipleTerms_passedCorrectly() throws Exception {
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(3, 2));

        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("messageMode", "TEXT")
                .param("terms", "insider AND trading\npump OR dump\n\"front running\"")
                .param("messageText", "message text"))
           .andExpect(status().isOk());

        verify(orchestrator).scan(
                eq(TermType.NATURAL_LANGUAGE),
                eq(new TermSource.TextTerms(List.of("insider AND trading", "pump OR dump", "\"front running\""))),
                eq(new MessageSource.SingleText("message text")),
                any(DisclaimerSource.class),
                eq("scanner-test-run"));
    }

    // ── POST /api/scan/json ──────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/scan/json with valid JSON body returns 200")
    void scanJson_validBody_returns200() throws Exception {
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(1, 1));

        String body = """
            {
              "inputMode": "TERMS",
              "messageMode": "TEXT",
              "terms": "insider AND trading",
              "messageText": "The insider bought shares.",
              "ruleName": "json-test"
            }
            """;

        mvc.perform(post("/api/scan/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("POST /api/scan/json with explicit termType=REGEX routes correctly")
    void scanJson_termTypeRegex_routesCorrectly() throws Exception {
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(1, 1));

        String body = """
            {
              "inputMode": "TERMS",
              "termType": "REGEX",
              "messageMode": "TEXT",
              "terms": "(?:insider|trading)",
              "messageText": "insider trading"
            }
            """;

        mvc.perform(post("/api/scan/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isOk());

        verify(orchestrator).scan(eq(TermType.REGEX), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any());
    }

    @Test
    @DisplayName("POST /api/scan/json with empty messageText returns 400")
    void scanJson_emptyMessage_returns400() throws Exception {
        String body = """
            {
              "inputMode": "TERMS",
              "messageMode": "TEXT",
              "terms": "insider AND trading",
              "messageText": ""
            }
            """;

        mvc.perform(post("/api/scan/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/scan/json with empty terms returns 400")
    void scanJson_emptyTerms_returns400() throws Exception {
        String body = """
            {
              "inputMode": "TERMS",
              "messageMode": "TEXT",
              "terms": "",
              "messageText": "some message"
            }
            """;

        mvc.perform(post("/api/scan/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Response includes htmlHighlightedMessage and termResults array")
    void scanMultipart_responseStructure_complete() throws Exception {
        when(orchestrator.scan(any(TermType.class), any(TermSource.class), any(MessageSource.class), any(DisclaimerSource.class), any()))
                .thenReturn(successResponse(1, 1));

        mvc.perform(multipart("/api/scan")
                .param("inputMode", "TERMS")
                .param("messageMode", "TEXT")
                .param("terms", "insider AND trading")
                .param("messageText", "The insider bought shares"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.scanId").isNotEmpty())
           .andExpect(jsonPath("$.scanDurationMs").isNumber())
           .andExpect(jsonPath("$.htmlHighlightedMessage").isString())
           .andExpect(jsonPath("$.termResults").isArray());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private ScanResponse successResponse(int total, int matched) {
        List<TermScanResult> terms = List.of(
                new TermScanResult("r::1", "insider AND trading",
                        List.of("(?:insider|trading)"), "PASS", true,
                        List.of(), matched > 0, false, false, null, null));

        MessageScanResult message = new MessageScanResult(
                "Pasted Message", "test message",
                "<mark class=\"lex-match\">insider</mark> trading",
                List.of(), terms, matched, matched > 0);

        return ScanResponse.success(
                UUID.randomUUID().toString(), 42L, "Natural Language",
                total, 0, 0,
                List.of(message), List.of());
    }
}
