package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.config.AppProperties;
import com.db.macs3.ecomms.spectre.model.Models.CompiledTerm;
import com.db.macs3.ecomms.spectre.model.Models.PatternValidationResult;
import com.db.macs3.ecomms.spectre.model.Models.RawMatch;
import com.gliwka.hyperscan.wrapper.CompileErrorException;
import com.gliwka.hyperscan.wrapper.Database;
import com.gliwka.hyperscan.wrapper.Expression;
import com.gliwka.hyperscan.wrapper.ExpressionFlag;
import com.gliwka.hyperscan.wrapper.Match;
import com.gliwka.hyperscan.wrapper.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core Hyperscan scanning service.
 *
 * <h2>{@code TermType.REGEX} only now</h2>
 * <p>Natural Language terms no longer reach this class at all — their AND
 * NOT / NEAR / FOLLOWEDBY evaluation happens entirely via
 * {@link ResolvedPatternMatcher} against the Compile Service's
 * {@code resolvedPatterns} field, in plain Java regex, never Hyperscan. See
 * {@link LexiconScanOrchestrator} class Javadoc for the full rationale
 * (Hyperscan's own documented eager, progressive {@code HS_FLAG_COMBINATION}
 * evaluation made the previous AND NOT design here unreliable, and plain
 * decomposition-by-AND could never verify a NEAR/FOLLOWEDBY distance to
 * begin with).
 *
 * <p>The only remaining caller is {@code TermType.REGEX} handling — a raw,
 * caller-supplied PCRE pattern with no operator-language syntax, so every
 * {@link CompiledTerm} this class ever receives now compiles as exactly one
 * plain expression. This removed the QUIET/COMBINATION expression building,
 * the auxiliary-id allocation, and the Stage 2 Java-regex text-recovery pass
 * a prior revision of this class needed — none of that machinery has a
 * caller left.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Receives {@link CompiledTerm} list (one plain pattern per term,
 *       already validated by {@link #validatePattern}).</li>
 *   <li>Builds one plain {@link Expression} per term, flagged
 *       {@code SOM_LEFTMOST} for accurate match-start reporting.</li>
 *   <li>Compiles a single Hyperscan {@link Database} from all expressions.</li>
 *   <li>Scans the message text with a {@link Scanner}.</li>
 *   <li>Returns a {@link RawMatch} list (byte-offset based; the caller
 *       converts to char indices) — a 1:1 conversion of Hyperscan's own
 *       match events, since no expression here is ever {@code COMBINATION}.</li>
 * </ol>
 *
 * <h2>Fallback strategy</h2>
 * <p>In some JVM environments (test runners, IDE debuggers) the Hyperscan
 * native scratch allocator throws {@code HS_ERR_INVALID} on {@code Scanner.scan()}.
 * When {@code lexicon.scanner.fallback-to-java-regex=true} (the default),
 * the service transparently retries with {@code java.util.regex.Pattern}
 * and synthesises equivalent {@link RawMatch} objects — see
 * {@link #scanWithJavaRegex}.
 *
 * <h2>Flag bitmask</h2>
 * <pre>
 *   1  = HS_FLAG_CASELESS
 *   2  = HS_FLAG_DOTALL
 *   32 = HS_FLAG_UTF8
 *   64 = HS_FLAG_UCP
 * </pre>
 */
@Service
public class HyperscanScanService {

    private static final Logger log = LoggerFactory.getLogger(HyperscanScanService.class);

    public static final int HS_FLAG_CASELESS = 1;
    public static final int HS_FLAG_DOTALL   = 2;
    public static final int HS_FLAG_UTF8     = 32;
    public static final int HS_FLAG_UCP      = 64;

    /**
     * Default Hyperscan flag bitmask applied to {@code TermType.REGEX} terms,
     * which bypass the Lexicon Compile Service entirely and therefore never
     * get script-aware flag selection from {@code ScriptDetector}. CASELESS
     * matches the platform-wide convention that lexicon matching is always
     * case-insensitive; UTF8 + UCP are required for correct multi-byte
     * (Korean, Chinese, Arabic, emoji, etc.) matching regardless of pattern
     * content, since Hyperscan must know the input is UTF-8 to interpret
     * character boundaries correctly. DOTALL is intentionally NOT included —
     * unlike AND/NOT-translated patterns, a raw user-supplied regex has no
     * implicit need for {@code .} to span newlines, and forcing it on could
     * silently change the meaning of a pattern the user wrote deliberately.
     */
    public static final int HS_FLAGS_REGEX_DEFAULT = HS_FLAG_CASELESS | HS_FLAG_UTF8 | HS_FLAG_UCP;

    private final AppProperties props;

    public HyperscanScanService(AppProperties props) {
        this.props = props;
    }

    /**
     * Compiles all terms into one Hyperscan database and scans the message text.
     *
     * @param compiledTerms list of terms that are ready for scanning
     * @param messageText   the communication message to scan (UTF-8 String)
     * @return list of raw matches (may be empty; never null)
     */
    public List<RawMatch> compileAndScan(List<CompiledTerm> compiledTerms, String messageText) {
        if (compiledTerms == null || compiledTerms.isEmpty()) {
            log.warn("compileAndScan called with no compiled terms — returning empty result");
            return Collections.emptyList();
        }
        if (messageText == null || messageText.isBlank()) {
            log.warn("compileAndScan called with blank message text — returning empty result");
            return Collections.emptyList();
        }

        log.info("Starting Hyperscan scan: {} terms, message length={} chars",
                compiledTerms.size(), messageText.length());

        try {
            return scanWithHyperscan(compiledTerms, messageText);
        } catch (Exception e) {
            if (props.getScanner().isFallbackToJavaRegex()) {
                log.warn("Hyperscan scan failed ({}), falling back to Java regex: {}",
                        e.getClass().getSimpleName(), e.getMessage());
                return scanWithJavaRegex(compiledTerms, messageText);
            }
            throw new HyperscanScanException("Hyperscan scan failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compiles {@code pattern} with the given Hyperscan flag bitmask and
     * returns {@code true} if compilation succeeds.
     * Used by unit tests to verify individual patterns without full scanning.
     *
     * @param pattern  Hyperscan PCRE pattern
     * @param hsFlags  bitmask of Hyperscan expression flags
     * @return true when Hyperscan accepts the pattern; false on compile error
     */
    public boolean validatePatternCompilation(String pattern, int hsFlags) {
        return validatePattern(pattern, hsFlags).pass();
    }

    /**
     * Compiles {@code pattern} with the given Hyperscan flag bitmask and
     * returns both the pass/fail outcome AND, on failure, Hyperscan's error
     * message — needed so {@code TermType.REGEX} terms (which bypass the
     * Lexicon Compile Service and therefore never receive a translation-stage
     * error) can still surface a meaningful compilation error on the UI,
     * exactly like Natural Language terms do when the Compile Service rejects them.
     *
     * @param pattern  Hyperscan PCRE pattern (as supplied directly by the user for REGEX-type terms)
     * @param hsFlags  bitmask of Hyperscan expression flags
     * @return a {@link com.db.macs3.ecomms.spectre.model.Models.PatternValidationResult}
     */
    public PatternValidationResult validatePattern(String pattern, int hsFlags) {
        EnumSet<ExpressionFlag> flags = toExpressionFlags(hsFlags);
        flags.add(ExpressionFlag.SOM_LEFTMOST);
        try (Database db = Database.compile(new Expression(pattern, flags, 0))) {
            log.debug("Pattern validation PASS: {}", pattern);
            return new PatternValidationResult(true, null);
        } catch (CompileErrorException e) {
            log.debug("Pattern validation FAIL: {} — {}", pattern, e.getMessage());
            return new PatternValidationResult(
                    false, "Hyperscan rejected this pattern: " + e.getMessage());
        } catch (Exception e) {
            log.debug("Pattern validation FAIL (unexpected): {} — {}", pattern, e.getMessage());
            return new PatternValidationResult(
                    false, "Pattern could not be compiled: " + e.getMessage());
        }
    }

    // ── Hyperscan scanning ────────────────────────────────────────────────────

    private List<RawMatch> scanWithHyperscan(List<CompiledTerm> terms, String messageText)
            throws CompileErrorException {

        List<Expression> expressions = buildExpressions(terms);

        try (Database db = Database.compile(expressions)) {
            log.debug("Hyperscan database compiled: {} expressions ({} terms)",
                    expressions.size(), terms.size());

            try (Scanner scanner = new Scanner()) {
                List<Match> matches = scanner.scan(db, messageText);

                log.info("Hyperscan scan complete: {} raw match event(s)", matches.size());

                List<RawMatch> results = new ArrayList<>(matches.size());
                for (Match m : matches) {
                    results.add(new RawMatch(
                            m.getStartPosition(), m.getEndPosition(), (int) m.getMatchedExpression().getId(), false));
                }
                return results;
            }
        }
    }

    /**
     * Builds one plain {@link Expression} per term — see class Javadoc,
     * "{@code TermType.REGEX} only now": every {@link CompiledTerm} this
     * class receives has exactly one entry in {@link CompiledTerm#requiredPatterns()}.
     */
    private List<Expression> buildExpressions(List<CompiledTerm> terms) {
        List<Expression> out = new ArrayList<>(terms.size());
        for (CompiledTerm term : terms) {
            EnumSet<ExpressionFlag> flags = toExpressionFlags(term.hsFlags());
            flags.add(ExpressionFlag.SOM_LEFTMOST);
            out.add(new Expression(term.requiredPatterns().get(0), flags, term.expressionIndex()));
        }
        return out;
    }

    // ── Java-regex fallback ────────────────────────────────────────────────────

    /**
     * Java regex fallback for environments where {@code Scanner.scan()} fails.
     * Reports every individual occurrence of each term's single required
     * pattern — see class Javadoc, "{@code TermType.REGEX} only now".
     */
    private List<RawMatch> scanWithJavaRegex(List<CompiledTerm> terms, String messageText) {
        log.debug("Java regex fallback scan: {} terms", terms.size());
        byte[] messageBytes = messageText.getBytes(StandardCharsets.UTF_8);
        List<RawMatch> results = new ArrayList<>();

        for (CompiledTerm term : terms) {
            int javaFlags = buildJavaRegexFlags(term.hsFlags());
            String pattern = term.requiredPatterns().get(0);
            try {
                Matcher matcher = Pattern.compile(pattern, javaFlags).matcher(messageText);
                while (matcher.find()) {
                    long startByte = charIndexToByteOffset(messageBytes, matcher.start());
                    long endByte   = charIndexToByteOffset(messageBytes, matcher.end());
                    results.add(new RawMatch(startByte, endByte, term.expressionIndex(), false));
                    log.trace("Regex match for '{}': [{},{}]",
                            term.termDescription(), matcher.start(), matcher.end());
                }
            } catch (Exception e) {
                log.warn("Java regex failed for pattern '{}': {}", pattern, e.getMessage());
            }
        }

        log.info("Java regex scan complete: {} matches found", results.size());
        return results;
    }

    // ── Flag conversion ───────────────────────────────────────────────────────

    /**
     * Converts the Hyperscan flag bitmask to a {@link Set} of {@link ExpressionFlag} enum values.
     * CASELESS is always added as the minimum flag.
     */
    public EnumSet<ExpressionFlag> toExpressionFlags(int bitmask) {
        EnumSet<ExpressionFlag> flags = EnumSet.noneOf(ExpressionFlag.class);
        if ((bitmask & HS_FLAG_CASELESS) != 0) flags.add(ExpressionFlag.CASELESS);
        if ((bitmask & HS_FLAG_DOTALL)   != 0) flags.add(ExpressionFlag.DOTALL);
        if ((bitmask & HS_FLAG_UTF8)     != 0) flags.add(ExpressionFlag.UTF8);
        if ((bitmask & HS_FLAG_UCP)      != 0) flags.add(ExpressionFlag.UCP);
        if (flags.isEmpty())                    flags.add(ExpressionFlag.CASELESS);
        return flags;
    }

    /**
     * Maps the Hyperscan flag bitmask to {@link java.util.regex.Pattern} flag bits.
     * Used by the fallback Java regex path.
     */
    private int buildJavaRegexFlags(int hsBitmask) {
        int jf = Pattern.UNICODE_CASE;
        if ((hsBitmask & HS_FLAG_CASELESS) != 0) jf |= Pattern.CASE_INSENSITIVE;
        if ((hsBitmask & HS_FLAG_DOTALL)   != 0) jf |= Pattern.DOTALL;
        if ((hsBitmask & HS_FLAG_UTF8)     != 0) jf |= Pattern.UNICODE_CHARACTER_CLASS;
        return jf;
    }

    // ── UTF-8 byte offset helpers ─────────────────────────────────────────────

    /**
     * Converts a Java {@link String} character index to a UTF-8 byte offset.
     *
     * <p>Hyperscan reports match positions as byte offsets into the UTF-8
     * representation of the input string, whereas Java uses UTF-16 char indices.
     * For ASCII-only content these are identical; for multi-byte Unicode
     * (Korean, Chinese, Arabic, emoji) they differ.
     *
     * @param utf8Bytes the UTF-8 encoding of the message
     * @param charIndex Java char index in the original String
     * @return corresponding byte offset in {@code utf8Bytes}
     */
    public static long charIndexToByteOffset(byte[] utf8Bytes, int charIndex) {
        long byteOffset = 0;
        int charsProcessed = 0;
        while (charsProcessed < charIndex && byteOffset < utf8Bytes.length) {
            byte b = utf8Bytes[(int) byteOffset];
            // Determine byte-length of this UTF-8 code unit
            int seqLen;
            if      ((b & 0x80) == 0)    seqLen = 1;  // ASCII
            else if ((b & 0xE0) == 0xC0) seqLen = 2;  // 2-byte sequence
            else if ((b & 0xF0) == 0xE0) seqLen = 3;  // 3-byte sequence (most CJK)
            else                          seqLen = 4;  // 4-byte sequence (emoji, etc.)
            byteOffset += seqLen;
            // 4-byte UTF-8 = one Unicode supplementary plane char → 2 Java chars (surrogate pair)
            charsProcessed += (seqLen == 4) ? 2 : 1;
        }
        return byteOffset;
    }

    /**
     * Converts a UTF-8 byte offset to a Java {@code String} character index.
     * Inverse of {@link #charIndexToByteOffset(byte[], int)}.
     */
    public static int byteOffsetToCharIndex(byte[] utf8Bytes, long byteOffset) {
        int charIndex = 0;
        long pos = 0;
        while (pos < byteOffset && pos < utf8Bytes.length) {
            byte b = utf8Bytes[(int) pos];
            int seqLen;
            if      ((b & 0x80) == 0)    seqLen = 1;
            else if ((b & 0xE0) == 0xC0) seqLen = 2;
            else if ((b & 0xF0) == 0xE0) seqLen = 3;
            else                          seqLen = 4;
            pos += seqLen;
            charIndex += (seqLen == 4) ? 2 : 1;
        }
        return charIndex;
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /** Thrown when Hyperscan scanning fails and fallback is disabled. */
    public static final class HyperscanScanException extends RuntimeException {
        public HyperscanScanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
