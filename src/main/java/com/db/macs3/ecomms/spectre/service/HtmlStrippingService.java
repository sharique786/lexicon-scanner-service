package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips HTML tags from a message and collapses whitespace, while building a
 * precise character-index mapping back to the ORIGINAL (unstripped) text —
 * required because Hyperscan must scan clean text (tags removed) but the UI
 * must highlight matches at their true position in the text the user pasted.
 *
 * <h2>Why stripping is necessary</h2>
 * <p>A term like {@code (Enjoy) FOLLOWEDBY{2} (Happy)} translates to a regex
 * such as {@code Enjoy(?:\s+\S+){0,2}\s+Happy}, which requires whitespace
 * between the two words. A message like
 * {@code "<p>Enjoy</p>\n<p>Happy Birthday</p>"} would never match that regex
 * as-is, because {@code Enjoy} and {@code Happy} are separated by markup, not
 * whitespace. Stripping tags — and replacing each removed tag (or run of
 * literal whitespace) with a single space — turns this into
 * {@code "Enjoy Happy Birthday"}, which the regex matches correctly.
 *
 * <h2>Why a plain "remove all tags" regex isn't used</h2>
 * <p>A naive {@code text.replaceAll("<[^>]*>", "")} would DELETE tags rather
 * than replace them with a boundary, collapsing {@code "Enjoy</p><p>Happy"}
 * into {@code "EnjoyHappy"} — which also fails to match, for the same reason
 * in reverse (no word-boundary left at all). Each tag match is therefore
 * replaced with exactly one space before whitespace collapsing runs.
 *
 * <h2>Why a real HTML parser is not used</h2>
 * <p>Compliance messages here are simple chat/email fragments (a handful of
 * {@code <p>}, {@code <br>}, {@code <span>}, {@code <b>}/{@code <i>} tags),
 * not full documents. A lightweight, well-formed-tag regex is sufficient and
 * avoids pulling in a full HTML/DOM dependency. Only strings that actually
 * look like an HTML tag (open, close, or self-closing, with optional
 * attributes) are treated as markup — a stray {@code '<'} in message text
 * such as {@code "if a < b then..."} does NOT match the tag pattern and is
 * left as an ordinary character, so real content is never silently eaten.
 *
 * <h2>Offset-mapping algorithm</h2>
 * <p>The original text is scanned once, left to right. At every position the
 * character is classified as one of:
 * <ul>
 *   <li><b>Tag</b> — matched by {@link #TAG_PATTERN}; the whole tag is
 *       skipped and treated as one whitespace unit, positioned at the tag's
 *       LAST character (its {@code '>'}).</li>
 *   <li><b>Literal whitespace</b> — space, tab, newline, carriage return;
 *       treated as one whitespace unit at that character's own position.</li>
 *   <li><b>Real content</b> — copied to the output as-is.</li>
 * </ul>
 * <p>Consecutive whitespace units (whether from tags, literal whitespace, or
 * a mix of both) are collapsed: only ONE space is ever emitted, and only once
 * more real content follows — this naturally trims leading and trailing
 * whitespace/tags as a side effect, with no separate trim step needed. The
 * emitted collapsed space is mapped to the ORIGINAL index of the LAST
 * whitespace-unit character in that run (i.e. the character immediately
 * preceding the next piece of real content).
 *
 * <p>For a matched span {@code [strippedStart, strippedEnd)} in the stripped
 * text, the corresponding original-text span is:
 * <pre>
 *   originalStart = mapping[strippedStart]
 *   originalEnd   = mapping[strippedEnd - 1] + 1
 * </pre>
 * i.e. the original index of the span's first stripped character, through
 * one past the original index of its last stripped character — the standard
 * exclusive-end convention, applied after mapping through to the original text.
 */
@Service
public class HtmlStrippingService {

    private static final Logger log = LoggerFactory.getLogger(HtmlStrippingService.class);

    /**
     * Matches a well-formed HTML tag: {@code <}, optional {@code /}, a tag
     * name starting with a letter, optional attributes (quoted or bare
     * values), optional trailing {@code /}, then {@code >}.
     * Deliberately does NOT match a bare {@code '<'} that isn't followed by
     * a plausible tag name — so stray less-than signs in ordinary text are
     * left untouched.
     */
    static final Pattern TAG_PATTERN = Pattern.compile(
            "</?[a-zA-Z][a-zA-Z0-9]*(?:\\s+[a-zA-Z][a-zA-Z0-9-]*"
            + "(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?)*\\s*/?>"
    );

    /**
     * Strips HTML tags from {@code originalText} and collapses whitespace,
     * returning both the cleaned text and the index mapping needed to
     * translate match positions back to the original.
     *
     * @param originalText the raw message text as supplied by the user (may contain HTML)
     * @return a {@link StrippedMessage} carrying the original text, the
     *         stripped text, and the offset-mapping table
     */
    public StrippedMessage strip(String originalText) {
        if (originalText == null || originalText.isEmpty()) {
            return new StrippedMessage(originalText == null ? "" : originalText, "", new int[0]);
        }

        // Step 1: locate every tag span up front so the main scan can jump over them in O(1).
        Matcher tagMatcher = TAG_PATTERN.matcher(originalText);
        // tagEndForStart[i] = index right after the tag starting at i, or -1 if no tag starts at i.
        int[] tagEndAt = new int[originalText.length()];
        java.util.Arrays.fill(tagEndAt, -1);
        int tagCount = 0;
        while (tagMatcher.find()) {
            tagEndAt[tagMatcher.start()] = tagMatcher.end();
            tagCount++;
        }

        StringBuilder out = new StringBuilder(originalText.length());
        int[] mapping = new int[originalText.length()]; // over-allocated; trimmed at the end
        int outLen = 0;

        boolean whitespacePending = false;
        int pendingOriginalIndex = -1;

        int i = 0;
        int n = originalText.length();
        while (i < n) {
            int tagEnd = tagEndAt[i];
            if (tagEnd != -1) {
                // Whole tag treated as one whitespace unit, anchored at its last character ('>').
                whitespacePending = true;
                pendingOriginalIndex = tagEnd - 1;
                i = tagEnd;
                continue;
            }

            char c = originalText.charAt(i);
            if (isWhitespace(c)) {
                whitespacePending = true;
                pendingOriginalIndex = i;
                i++;
                continue;
            }

            // Real content character.
            if (whitespacePending && outLen > 0) {
                out.append(' ');
                mapping[outLen++] = pendingOriginalIndex;
            }
            whitespacePending = false;
            out.append(c);
            mapping[outLen++] = i;
            i++;
        }
        // Trailing pending whitespace (if any) is intentionally never emitted — trims the end.

        int[] trimmedMapping = java.util.Arrays.copyOf(mapping, outLen);
        String strippedText = out.toString();

        log.debug("HTML strip: {} tag(s) removed, {} chars -> {} chars",
                tagCount, originalText.length(), strippedText.length());

        return new StrippedMessage(originalText, strippedText, trimmedMapping);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}
