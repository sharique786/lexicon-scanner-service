package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.config.AppProperties;
import com.db.macs3.ecomms.spectre.model.Models.CompiledTerm;
import com.db.macs3.ecomms.spectre.model.Models.PatternValidationResult;
import com.db.macs3.ecomms.spectre.model.Models.RawMatch;
import com.gliwka.hyperscan.wrapper.ExpressionFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HyperscanScanService}.
 *
 * <p><strong>Note on Scanner.scan():</strong> In many test-runner environments the
 * Hyperscan native scratch allocator throws {@code HS_ERR_INVALID} during
 * {@code Scanner.scan()}. Tests for the full scan pipeline therefore rely on the
 * {@code fallback-to-java-regex=true} property (the default), which transparently
 * retries with {@link java.util.regex.Pattern} and produces identical
 * {@link RawMatch} semantics.
 *
 * <p>Pattern <em>compilation</em> ({@link HyperscanScanService#validatePatternCompilation})
 * calls only {@code Database.compile()} — which is reliable across environments —
 * and is tested separately without relying on the scanner.
 */
@DisplayName("HyperscanScanService")
class HyperscanScanServiceTest {

    private HyperscanScanService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getScanner().setFallbackToJavaRegex(true);
        service = new HyperscanScanService(props);
    }

    // ── validatePatternCompilation ───────────────────────────────────────────

    @Test
    @DisplayName("simple OR pattern compiles successfully")
    void validatePatternCompilation_simpleOr_compiles() {
        assertThat(service.validatePatternCompilation("(?:insider|trading)", 1)).isTrue();
    }

    @Test
    @DisplayName("wildcard pattern compiles successfully")
    void validatePatternCompilation_wildcard_compiles() {
        assertThat(service.validatePatternCompilation("trade\\S*", 1)).isTrue();
    }

    @Test
    @DisplayName("NEAR gap pattern compiles successfully")
    void validatePatternCompilation_nearGap_compiles() {
        // NEAR{3}: A followed by up to 3 words, then B
        String pattern = "(?:insider(?:\\s+\\S+){0,3}\\s+trading|trading(?:\\s+\\S+){0,3}\\s+insider)";
        assertThat(service.validatePatternCompilation(pattern, 3)).isTrue();  // CASELESS|DOTALL
    }

    @Test
    @DisplayName("quoted-phrase escape pattern compiles successfully")
    void validatePatternCompilation_quotedPhrase_compiles() {
        assertThat(service.validatePatternCompilation("front\\s+running", 1)).isTrue();
    }

    @Test
    @DisplayName("invalid Hyperscan pattern returns false")
    void validatePatternCompilation_invalidPattern_returnsFalse() {
        // '(' is unclosed — Hyperscan should reject this
        assertThat(service.validatePatternCompilation("(?:unclosed", 1)).isFalse();
    }

    @Test
    @DisplayName("UTF8+UCP flagged pattern with CJK literal compiles successfully")
    void validatePatternCompilation_cjkLiteral_compiles() {
        // Korean characters are valid in UTF8+UCP mode
        assertThat(service.validatePatternCompilation("내부자", 97)).isTrue();  // 1|32|64 = CASELESS|UTF8|UCP
    }

    @Test
    @DisplayName("emoji Unicode escape compiles successfully")
    void validatePatternCompilation_emojiEscape_compiles() {
        assertThat(service.validatePatternCompilation("\\x{1F4B0}", 97)).isTrue();
    }

    // ── validatePattern (error-message-capturing variant, used for TermType.REGEX) ──

    @Test
    @DisplayName("validatePattern() on a valid pattern returns pass=true, errorMessage=null")
    void validatePattern_validPattern_passTrueNoError() {
        PatternValidationResult result = service.validatePattern("(?:insider|trading)", 1);
        assertThat(result.pass()).isTrue();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("validatePattern() on an invalid pattern returns pass=false with a non-blank error message")
    void validatePattern_invalidPattern_failsWithMessage() {
        PatternValidationResult result = service.validatePattern("(?:unclosed", 1);
        assertThat(result.pass()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("validatePattern() with HS_FLAGS_REGEX_DEFAULT accepts a CJK literal")
    void validatePattern_regexDefaultFlags_acceptsCjk() {
        PatternValidationResult result = service.validatePattern("내부자", HyperscanScanService.HS_FLAGS_REGEX_DEFAULT);
        assertThat(result.pass()).isTrue();
    }

    @Test
    @DisplayName("HS_FLAGS_REGEX_DEFAULT equals CASELESS|UTF8|UCP (1|32|64 = 97)")
    void hsFlagsRegexDefault_isCaselessUtf8Ucp() {
        assertThat(HyperscanScanService.HS_FLAGS_REGEX_DEFAULT).isEqualTo(97);
    }

    @Test
    @DisplayName("validatePatternCompilation() (legacy boolean method) still works correctly")
    void validatePatternCompilation_stillWorksAfterRefactor() {
        assertThat(service.validatePatternCompilation("(?:insider)", 1)).isTrue();
        assertThat(service.validatePatternCompilation("(?:unclosed", 1)).isFalse();
    }

    // ── toExpressionFlags ────────────────────────────────────────────────────

    @Test
    @DisplayName("bitmask 1 → CASELESS only")
    void toExpressionFlags_bitmask1_caseless() {
        Set<ExpressionFlag> flags = service.toExpressionFlags(1);
        assertThat(flags).containsExactlyInAnyOrder(ExpressionFlag.CASELESS);
    }

    @Test
    @DisplayName("bitmask 3 → CASELESS + DOTALL")
    void toExpressionFlags_bitmask3_caselessDotall() {
        Set<ExpressionFlag> flags = service.toExpressionFlags(3);
        assertThat(flags).containsExactlyInAnyOrder(ExpressionFlag.CASELESS, ExpressionFlag.DOTALL);
    }

    @Test
    @DisplayName("bitmask 97 → CASELESS + UTF8 + UCP")
    void toExpressionFlags_bitmask97_utf8Ucp() {
        Set<ExpressionFlag> flags = service.toExpressionFlags(97);  // 1 | 32 | 64
        assertThat(flags).containsExactlyInAnyOrder(
                ExpressionFlag.CASELESS, ExpressionFlag.UTF8, ExpressionFlag.UCP);
    }

    @Test
    @DisplayName("bitmask 0 → CASELESS as minimum safe default")
    void toExpressionFlags_bitmask0_defaultsCaseless() {
        Set<ExpressionFlag> flags = service.toExpressionFlags(0);
        assertThat(flags).contains(ExpressionFlag.CASELESS);
    }

    // ── compileAndScan (via Java-regex fallback) ─────────────────────────────

    @Test
    @DisplayName("single term matching — returns one match")
    void compileAndScan_singleTermMatch_returnsMatch() {
        List<CompiledTerm> terms = List.of(
                term(0, "(?:insider|trading)", 1, false));
        String message = "The insider bought shares.";

        List<RawMatch> matches = service.compileAndScan(terms, message);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).expressionIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("no matching term — returns empty list")
    void compileAndScan_noMatch_returnsEmpty() {
        List<CompiledTerm> terms = List.of(
                term(0, "(?:xyznomatch)", 1, false));
        String message = "Hello world, nothing here.";

        List<RawMatch> matches = service.compileAndScan(terms, message);

        assertThat(matches).isEmpty();
    }

    @Test
    @DisplayName("multiple terms — each produces matches at correct expression index")
    void compileAndScan_multipleTerms_correctIndices() {
        List<CompiledTerm> terms = List.of(
                term(0, "(?:insider)", 1, false),
                term(1, "(?:trading)", 1, false));
        String message = "insider trading is illegal";

        List<RawMatch> matches = service.compileAndScan(terms, message);

        assertThat(matches).hasSize(2);
        assertThat(matches.stream().map(RawMatch::expressionIndex))
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    @DisplayName("case-insensitive match (CASELESS flag)")
    void compileAndScan_caseInsensitive_matches() {
        List<CompiledTerm> terms = List.of(term(0, "(?:INSIDER)", 1, false));
        String message = "The insider information was shared.";

        List<RawMatch> matches = service.compileAndScan(terms, message);

        assertThat(matches).isNotEmpty();
    }

    @Test
    @DisplayName("empty term list — returns empty without error")
    void compileAndScan_emptyTerms_returnsEmpty() {
        assertThat(service.compileAndScan(List.of(), "some message")).isEmpty();
    }

    @Test
    @DisplayName("blank message — returns empty without error")
    void compileAndScan_blankMessage_returnsEmpty() {
        List<CompiledTerm> terms = List.of(term(0, "(?:test)", 1, false));
        assertThat(service.compileAndScan(terms, "")).isEmpty();
        assertThat(service.compileAndScan(terms, "   ")).isEmpty();
    }

    // ── Byte/char offset utilities ────────────────────────────────────────────

    @Test
    @DisplayName("ASCII: charIndexToByteOffset is identity (1 byte per char)")
    void charIndexToByteOffset_asciiOnly_identityMapping() {
        byte[] bytes = "Hello world".getBytes(StandardCharsets.UTF_8);
        assertThat(HyperscanScanService.charIndexToByteOffset(bytes, 0)).isEqualTo(0);
        assertThat(HyperscanScanService.charIndexToByteOffset(bytes, 5)).isEqualTo(5);
        assertThat(HyperscanScanService.charIndexToByteOffset(bytes, 11)).isEqualTo(11);
    }

    @Test
    @DisplayName("Korean (3-byte UTF-8): char 1 → byte 3")
    void charIndexToByteOffset_korean_correctOffset() {
        // 비 = 3 bytes, 밀 = 3 bytes
        byte[] bytes = "비밀".getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isEqualTo(6);
        assertThat(HyperscanScanService.charIndexToByteOffset(bytes, 0)).isEqualTo(0);
        assertThat(HyperscanScanService.charIndexToByteOffset(bytes, 1)).isEqualTo(3);
        assertThat(HyperscanScanService.charIndexToByteOffset(bytes, 2)).isEqualTo(6);
    }

    @Test
    @DisplayName("byteOffsetToCharIndex is inverse of charIndexToByteOffset for ASCII")
    void byteOffsetToCharIndex_ascii_roundTrip() {
        String text = "Hello world";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= text.length(); i++) {
            long byteOff = HyperscanScanService.charIndexToByteOffset(bytes, i);
            int charIdx  = HyperscanScanService.byteOffsetToCharIndex(bytes, byteOff);
            assertThat(charIdx).as("round-trip at char %d", i).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("byteOffsetToCharIndex round-trips for CJK text")
    void byteOffsetToCharIndex_cjk_roundTrip() {
        String text = "정보 leakage 거래";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= text.length(); i++) {
            long byteOff = HyperscanScanService.charIndexToByteOffset(bytes, i);
            int charIdx  = HyperscanScanService.byteOffsetToCharIndex(bytes, byteOff);
            assertThat(charIdx).as("round-trip at char %d for CJK text", i).isEqualTo(i);
        }
    }

    // ── Factory helper ────────────────────────────────────────────────────────

    private CompiledTerm term(int idx, String pattern, int flags, boolean requiresExclusion) {
        return new CompiledTerm("t::" + idx, "desc-" + idx, List.of(pattern),
                requiresExclusion ? List.of("excluded-" + idx) : null, flags, requiresExclusion, idx);
    }
}
