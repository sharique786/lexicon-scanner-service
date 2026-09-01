package com.db.macs3.ecomms.spectre.client;

import com.db.macs3.ecomms.spectre.client.LexiconCompileClient.LexiconCompileException;
import com.db.macs3.ecomms.spectre.config.AppProperties;
import com.db.macs3.ecomms.spectre.model.Models.CompileServiceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link LexiconCompileClient} using Spring's {@link MockRestServiceServer}.
 *
 * <p>All HTTP interactions are mocked — no real Lexicon Compile Service instance required.
 */
@DisplayName("LexiconCompileClient")
class LexiconCompileClientTest {

    private static final String BASE_URL = "http://lexicon-compile-service:8080";
    private static final String COMPILE_URL = BASE_URL + "/api/lexicon/compile";
    private static final String COMPILE_CSV_URL = BASE_URL + "/api/lexicon/compile/csv";

    private MockRestServiceServer mockServer;
    private LexiconCompileClient client;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCompileService().setBaseUrl(BASE_URL);
        props.getCompileService().setDefaultRuleName("test-rule");

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        // Bind MockRestServiceServer via the underlying RestTemplate exchange adapter
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        client = new LexiconCompileClient(restClient, props);
    }

    // ── compile() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("compile() returns deserialized response on HTTP 200")
    void compile_success_returnsResponse() {
        mockServer.expect(requestTo(COMPILE_URL))
                  .andExpect(method(HttpMethod.POST))
                  .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        CompileServiceResponse response = client.compile(
                List.of("insider AND trading", "pump OR dump"), "test-session");

        assertThat(response).isNotNull();
        assertThat(response.totalTerms()).isEqualTo(2);
        assertThat(response.passCount()).isEqualTo(2);
        assertThat(response.hasFailures()).isFalse();
        assertThat(response.results()).hasSize(2);

        mockServer.verify();
    }

    @Test
    @DisplayName("compile() PASS term has regexPattern, exclusionRegex, and resolvedPatterns")
    void compile_passTerm_hasPatternAndFlags() {
        mockServer.expect(requestTo(COMPILE_URL))
                  .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        CompileServiceResponse response =
                client.compile(List.of("insider AND trading"), null);

        CompileServiceResponse.TermResult first = response.results().get(0);
        assertThat(first.compilationStatus()).isEqualTo("PASS");
        assertThat(first.regexPattern()).containsExactly("insider");
        assertThat(first.exclusionRegex()).containsExactly("(?:trading)");
        assertThat(first.resolvedPatterns()).isEqualTo("insider AND NOT ((?:trading))");
        assertThat(first.requiresExclusionCheck()).isTrue();
    }

    @Test
    @DisplayName("compile() with failed term — hasFailures=true, errorLog populated")
    void compile_failedTerm_hasFailuresTrue() {
        mockServer.expect(requestTo(COMPILE_URL))
                  .andRespond(withSuccess(failureBody(), MediaType.APPLICATION_JSON));

        CompileServiceResponse response =
                client.compile(List.of("(unclosed pattern"), null);

        assertThat(response.hasFailures()).isTrue();
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.results().get(0).compilationStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("compile() throws LexiconCompileException on HTTP 500")
    void compile_serverError_throwsException() {
        mockServer.expect(requestTo(COMPILE_URL))
                  .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.compile(List.of("insider AND trading"), null))
                .isInstanceOf(LexiconCompileException.class)
                .hasMessageContaining("server error");
    }

    @Test
    @DisplayName("compile() throws LexiconCompileException on HTTP 400")
    void compile_clientError_throwsException() {
        mockServer.expect(requestTo(COMPILE_URL))
                  .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.compile(List.of("bad request"), null))
                .isInstanceOf(LexiconCompileException.class)
                .hasMessageContaining("client error");
    }

    @Test
    @DisplayName("compile() with null term list returns empty response without HTTP call")
    void compile_nullTermList_returnsEmptyWithoutCall() {
        // No mock expectation — no HTTP call should be made
        CompileServiceResponse response = client.compile(null, "any-rule");
        assertThat(response.totalTerms()).isEqualTo(0);
        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("compile() uses default rule name when ruleName is blank")
    void compile_blankRuleName_usesDefault() {
        mockServer.expect(requestTo(COMPILE_URL))
                  .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        // Should not throw — default rule name is used
        CompileServiceResponse response = client.compile(List.of("insider AND trading"), "");
        assertThat(response).isNotNull();
        mockServer.verify();
    }

    // ── compileCsv() ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("compileCsv() forwards the file to /compile/csv and returns deserialized response")
    void compileCsv_success_returnsResponse() {
        mockServer.expect(requestTo(COMPILE_CSV_URL))
                  .andExpect(method(HttpMethod.POST))
                  .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        MultipartFile csv = new MockMultipartFile(
                "file", "terms.csv", "text/csv",
                "term_id,term_description\nr::1,insider AND trading\nr::2,pump OR dump\n"
                        .getBytes(StandardCharsets.UTF_8));

        CompileServiceResponse response = client.compileCsv(csv, "test-session");

        assertThat(response).isNotNull();
        assertThat(response.totalTerms()).isEqualTo(2);
        assertThat(response.passCount()).isEqualTo(2);
        mockServer.verify();
    }

    @Test
    @DisplayName("compileCsv() throws LexiconCompileException on HTTP 4xx (e.g. malformed CSV)")
    void compileCsv_clientError_throwsException() {
        mockServer.expect(requestTo(COMPILE_CSV_URL))
                  .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        MultipartFile csv = new MockMultipartFile(
                "file", "terms.csv", "text/csv", "not,a,valid,csv\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.compileCsv(csv, null))
                .isInstanceOf(LexiconCompileException.class)
                .hasMessageContaining("rejected the CSV");
    }

    @Test
    @DisplayName("compileCsv() throws LexiconCompileException on HTTP 500")
    void compileCsv_serverError_throwsException() {
        mockServer.expect(requestTo(COMPILE_CSV_URL))
                  .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        MultipartFile csv = new MockMultipartFile(
                "file", "terms.csv", "text/csv", "term_id,term_description\nr::1,test\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.compileCsv(csv, null))
                .isInstanceOf(LexiconCompileException.class)
                .hasMessageContaining("server error");
    }

    @Test
    @DisplayName("compileCsv() with null file returns empty response without HTTP call")
    void compileCsv_nullFile_returnsEmptyWithoutCall() {
        // No mock expectation — no HTTP call should be made
        CompileServiceResponse response = client.compileCsv(null, "any-rule");
        assertThat(response.totalTerms()).isEqualTo(0);
        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("compileCsv() with empty file returns empty response without HTTP call")
    void compileCsv_emptyFile_returnsEmptyWithoutCall() {
        MultipartFile empty = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);
        CompileServiceResponse response = client.compileCsv(empty, "any-rule");
        assertThat(response.totalTerms()).isEqualTo(0);
    }

    // ── Response JSON fixtures ────────────────────────────────────────────────

    private String successBody() {
        return """
            {
              "lexiconRuleName": "test-rule",
              "totalTerms": 2,
              "passCount": 2,
              "failedCount": 0,
              "hasFailures": false,
              "results": [
                {
                  "termId": "test-rule::1",
                  "termDescription": "insider AND NOT trading",
                  "compilationStatus": "PASS",
                  "regexPattern": ["insider"],
                  "requiresExclusionCheck": true,
                  "exclusionRegex": ["(?:trading)"],
                  "resolvedPatterns": "insider AND NOT ((?:trading))"
                },
                {
                  "termId": "test-rule::2",
                  "termDescription": "pump OR dump",
                  "compilationStatus": "PASS",
                  "regexPattern": ["(?:pump|dump)"],
                  "requiresExclusionCheck": false,
                  "resolvedPatterns": "(?:pump|dump)"
                }
              ]
            }
            """;
    }

    private String failureBody() {
        return """
            {
              "lexiconRuleName": "test-rule",
              "totalTerms": 1,
              "passCount": 0,
              "failedCount": 1,
              "hasFailures": true,
              "results": [
                {
                  "termId": "test-rule::1",
                  "termDescription": "(unclosed pattern",
                  "compilationStatus": "FAILED",
                  "requiresExclusionCheck": false,
                  "errorLog": "Syntax error: unclosed group"
                }
              ]
            }
            """;
    }
}
