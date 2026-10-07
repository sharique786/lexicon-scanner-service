package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.service.CsvParserService.CsvParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CsvParserService}.
 * No Spring context — instantiated directly for fast, isolated execution.
 */
@DisplayName("CsvParserService")
class CsvParserServiceTest {

    private CsvParserService service;

    @BeforeEach
    void setUp() {
        service = new CsvParserService();
    }

    // ── parseTermDescriptions ─────────────────────────────────────────────────

    @Test
    @DisplayName("parses three-column CSV with header and returns term descriptions")
    void parseTermDescriptions_standardCsv_returnsDescriptions() {
        String csv = "term_id,term_description,risk_driver_name\n"
                   + "rule1::1,insider AND trading,Market Abuse\n"
                   + "rule1::2,pump OR dump,Market Manipulation\n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result)
                .hasSize(2)
                .containsExactly("insider AND trading", "pump OR dump");
    }

    @Test
    @DisplayName("skips header row when first column is 'term_id'")
    void parseTermDescriptions_skipsHeaderRow() {
        String csv = "term_id,term_description\n"
                   + "t::1,price NEAR{3} rigging\n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result).containsExactly("price NEAR{3} rigging");
    }

    @Test
    @DisplayName("strips UTF-8 BOM produced by Excel exports")
    void parseTermDescriptions_stripsBom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String csvBody = "t::1,insider AND trading,MA\n";
        byte[] content = concat(bom, csvBody.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile("file", "bom.csv",
                "text/csv", content);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result).containsExactly("insider AND trading");
    }

    @Test
    @DisplayName("skips blank rows silently")
    void parseTermDescriptions_skipsBlankRows() {
        String csv = "term_id,term_description\n"
                   + "\n"
                   + "t::1,spoofing OR layering\n"
                   + "   \n"
                   + "t::2,\"front running\"\n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        // A quoted CSV field is unquoted by the parser (RFC 4180), so the quotes are not part of the term.
        assertThat(result).containsExactly("spoofing OR layering", "front running");
    }

    @Test
    @DisplayName("skips rows starting with '#' (comment rows)")
    void parseTermDescriptions_skipsCommentRows() {
        String csv = "term_id,term_description\n"
                   + "# this is a comment\n"
                   + "t::1,market manipulation\n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result).containsExactly("market manipulation");
    }

    @Test
    @DisplayName("skips rows that have fewer than 2 columns")
    void parseTermDescriptions_skipsShortRows() {
        String csv = "term_id,term_description\n"
                   + "t::1\n"
                   + "t::2,valid term\n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result).containsExactly("valid term");
    }

    @Test
    @DisplayName("returns empty list for null or empty file")
    void parseTermDescriptions_nullOrEmptyFile_returnsEmpty() {
        assertThat(service.parseTermDescriptions(null)).isEmpty();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.csv",
                "text/csv", new byte[0]);
        assertThat(service.parseTermDescriptions(empty)).isEmpty();
    }

    @Test
    @DisplayName("handles CSV with quoted terms containing commas")
    void parseTermDescriptions_quotedTermsWithCommas() {
        String csv = "term_id,term_description,risk\n"
                   + "\"t::1\",\"spoofing, layering OR manipulation\",MA\n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result).containsExactly("spoofing, layering OR manipulation");
    }

    @Test
    @DisplayName("parses CSV without header row (no 'term_id' first cell)")
    void parseTermDescriptions_noHeaderRow() {
        String csv = "t::1,insider AND trading,Market Abuse\n"
                   + "t::2,pump OR dump,Market Manipulation\n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result).containsExactly("insider AND trading", "pump OR dump");
    }

    @Test
    @DisplayName("strips leading/trailing whitespace from descriptions")
    void parseTermDescriptions_stripsWhitespace() {
        String csv = "term_id,term_description\n"
                   + "t::1,  insider AND trading  \n";
        MockMultipartFile file = multipart("terms.csv", csv);

        List<String> result = service.parseTermDescriptions(file);

        assertThat(result).containsExactly("insider AND trading");
    }

    // ── parseTermDescriptionsFromText ────────────────────────────────────────

    @Test
    @DisplayName("parseTermDescriptionsFromText parses inline CSV text")
    void parseTermDescriptionsFromText_validText() {
        String csv = "term_id,term_description\nt::1,pump AND dump\n";

        List<String> result = service.parseTermDescriptionsFromText(csv);

        assertThat(result).containsExactly("pump AND dump");
    }

    @Test
    @DisplayName("parseTermDescriptionsFromText returns empty for blank input")
    void parseTermDescriptionsFromText_blank_returnsEmpty() {
        assertThat(service.parseTermDescriptionsFromText(null)).isEmpty();
        assertThat(service.parseTermDescriptionsFromText("   ")).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MockMultipartFile multipart(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
