package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.Models.CompiledTerm;
import com.db.macs3.ecomms.spectre.model.Models.MatchHighlight;
import com.db.macs3.ecomms.spectre.model.Models.RawMatch;
import com.db.macs3.ecomms.spectre.model.Models.StrippedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts raw Hyperscan byte-offset matches into human-readable
 * {@link MatchHighlight} records and HTML with {@code <mark>} tags.
 *
 * <h2>UTF-8 / char index mapping</h2>
 * <p>Hyperscan operates on UTF-8 byte streams and reports match positions
 * as byte offsets. Java {@link String} uses UTF-16 internally, so multi-byte
 * Unicode characters (Korean, Chinese, Arabic, emoji) have different byte
 * and char offsets. This service converts byte offsets to Java char indices
 * via {@link HyperscanScanService#byteOffsetToCharIndex(byte[], long)}.
 *
 * <h2>HTML-stripped text vs. original text</h2>
 * <p>Hyperscan scans the HTML-stripped, whitespace-collapsed text (see
 * {@link HtmlStrippingService}), so raw byte offsets are first converted to
 * char indices IN THE STRIPPED TEXT. Two different things are then derived
 * from that stripped-text span:
 * <ul>
 *   <li>{@link MatchHighlight#matchedText()} — the clean matched substring,
 *       extracted directly from the STRIPPED text (e.g. {@code "Enjoy Happy"}
 *       even when the original had markup between the two words).</li>
 *   <li>{@link MatchHighlight#startCharIndex()} / {@link MatchHighlight#endCharIndex()}
 *       — mapped back to the ORIGINAL text via {@link StrippedMessage#mapSpanToOriginal},
 *       so highlighting lands on the correct position in the text the user
 *       actually pasted, tags and all.</li>
 * </ul>
 *
 * <h2>Overlap handling</h2>
 * <p>When multiple terms match the same region of the message, overlapping
 * {@link MatchHighlight} spans (in ORIGINAL-text coordinates) are merged into
 * a single contiguous span before building the HTML, so {@code <mark>} tags
 * never nest.
 *
 * <h2>HTML safety</h2>
 * <p>All non-matched text is passed through Spring's {@link HtmlUtils#htmlEscape}
 * to prevent XSS when the highlighted HTML is rendered in the browser.
 */
@Service
public class MatchHighlightService {

    private static final Logger log = LoggerFactory.getLogger(MatchHighlightService.class);

    private static final String MARK_OPEN_TPLT = "<mark class=\"lex-match\" data-term=\"%s\" title=\"%s\">";
    private static final String MARK_OPEN_GENERIC = "<mark class=\"lex-match\">";
    private static final String MARK_OPEN_DISCLAIMER = "<mark class=\"lex-match-disclaimer\" title=\"Matched in disclaimer text — not counted as an alert\">";
    private static final String MARK_CLOSE = "</mark>";

    /**
     * Groups raw Hyperscan matches by their expression index, converts byte
     * offsets (into the STRIPPED text) to {@link MatchHighlight} records
     * whose positions are mapped back to the ORIGINAL text, and returns one
     * list of highlights per compiled term.
     *
     * @param rawMatches      list of raw matches from Hyperscan (byte offsets into the stripped text)
     * @param compiledTerms   the terms that were compiled (index → term mapping)
     * @param strippedMessage the original/stripped text pair plus offset mapping
     *                        (see {@link HtmlStrippingService})
     * @return map from expression index → list of highlights (ORIGINAL-text char-offset based)
     */
    public Map<Integer, List<MatchHighlight>> groupMatchesByTerm(
            List<RawMatch> rawMatches,
            List<CompiledTerm> compiledTerms,
            StrippedMessage strippedMessage) {

        if (rawMatches.isEmpty()) {
            return Map.of();
        }

        String strippedText = strippedMessage.strippedText();
        byte[] strippedBytes = strippedText.getBytes(StandardCharsets.UTF_8);

        return rawMatches.stream()
                .collect(Collectors.groupingBy(
                        RawMatch::expressionIndex,
                        Collectors.mapping(
                                raw -> toHighlight(raw, strippedBytes, strippedText, strippedMessage),
                                Collectors.toList())));
    }

    /**
     * Builds a full-message HTML string with {@code <mark>} tags wrapping every
     * matched region. Overlapping matches from different terms are merged.
     *
     * <p>{@code messageText} here is the ORIGINAL (un-stripped) message — the
     * one the user pasted, tags and all. {@code allHighlights} spans are in
     * ORIGINAL-text coordinates (see {@link #groupMatchesByTerm}), so both the
     * unmatched "before/after" segments AND the highlighted segments are
     * sliced directly from {@code messageText} itself, not from
     * {@link MatchHighlight#matchedText()} (which holds the STRIPPED text's
     * clean match for API/JSON display — see class Javadoc). This means a
     * match spanning what was originally two HTML blocks correctly shows the
     * original markup (HTML-escaped, so it renders as visible text) inside
     * the {@code <mark>}, giving an accurate picture of exactly what was matched.
     *
     * @param messageText   the ORIGINAL message text (not the HTML-stripped version)
     * @param allHighlights all match highlights to render, in ORIGINAL-text coordinates
     * @return HTML-safe string with {@code <mark class="lex-match">} spans
     */
    public String buildHighlightedHtml(String messageText, List<MatchHighlight> allHighlights) {
        if (allHighlights.isEmpty()) {
            log.debug("No matches — returning HTML-escaped plain message");
            return HtmlUtils.htmlEscape(messageText);
        }

        List<MatchHighlight> merged = mergeOverlapping(allHighlights);
        log.debug("Building highlighted HTML: {} merged spans from {} raw highlights",
                merged.size(), allHighlights.size());

        StringBuilder sb = new StringBuilder(messageText.length() + merged.size() * 50);
        int pos = 0;

        for (MatchHighlight span : merged) {
            // Safe text before this match
            if (span.startCharIndex() > pos) {
                sb.append(HtmlUtils.htmlEscape(
                        messageText.substring(pos, span.startCharIndex())));
            }
            // Highlighted match — sliced from the ORIGINAL text at the mapped
            // position, so any markup within the matched span is shown
            // (escaped) rather than silently replaced by the clean stripped text.
            int safeStart = Math.max(0, Math.min(span.startCharIndex(), messageText.length()));
            int safeEnd   = Math.max(safeStart, Math.min(span.endCharIndex(), messageText.length()));
            sb.append(span.inDisclaimer() ? MARK_OPEN_DISCLAIMER : MARK_OPEN_GENERIC)
              .append(HtmlUtils.htmlEscape(messageText.substring(safeStart, safeEnd)))
              .append(MARK_CLOSE);
            pos = span.endCharIndex();
        }

        // Remaining text after last match
        if (pos < messageText.length()) {
            sb.append(HtmlUtils.htmlEscape(messageText.substring(pos)));
        }

        return sb.toString();
    }

    /**
     * Variant that annotates each {@code <mark>} with the term that produced it.
     * Used when the caller wants to colour-code matches by term in the UI.
     *
     * <p>As with {@link #buildHighlightedHtml}, {@code messageText} must be
     * the ORIGINAL text and highlight spans must already be in ORIGINAL-text
     * coordinates; the highlighted portion is sliced from {@code messageText}
     * itself rather than from each highlight's {@code matchedText()}.
     *
     * @param messageText       the ORIGINAL message text
     * @param highlightsByTerm  map from term description → highlight list (ORIGINAL-text coordinates)
     * @return HTML with per-term data attributes on {@code <mark>} tags
     */
    public String buildAnnotatedHighlightedHtml(
            String messageText,
            Map<String, List<MatchHighlight>> highlightsByTerm) {

        if (highlightsByTerm.isEmpty()) {
            return HtmlUtils.htmlEscape(messageText);
        }

        // Flatten + tag each highlight with its term
        List<TaggedHighlight> tagged = highlightsByTerm.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(h -> new TaggedHighlight(h, e.getKey())))
                .sorted(Comparator.comparingInt(th -> th.h().startCharIndex()))
                .toList();

        StringBuilder sb = new StringBuilder();
        int pos = 0;

        for (TaggedHighlight th : mergeTagged(tagged)) {
            if (th.h().startCharIndex() > pos) {
                sb.append(HtmlUtils.htmlEscape(messageText.substring(pos, th.h().startCharIndex())));
            }
            int safeStart = Math.max(0, Math.min(th.h().startCharIndex(), messageText.length()));
            int safeEnd   = Math.max(safeStart, Math.min(th.h().endCharIndex(), messageText.length()));
            sb.append(String.format(MARK_OPEN_TPLT,
                    HtmlUtils.htmlEscape(th.termDesc()),
                    HtmlUtils.htmlEscape(th.termDesc())))
              .append(HtmlUtils.htmlEscape(messageText.substring(safeStart, safeEnd)))
              .append(MARK_CLOSE);
            pos = th.h().endCharIndex();
        }

        if (pos < messageText.length()) {
            sb.append(HtmlUtils.htmlEscape(messageText.substring(pos)));
        }

        return sb.toString();
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Converts a {@link RawMatch} (byte offsets into the STRIPPED text) to a
     * {@link MatchHighlight} whose {@code matchedText} is the clean substring
     * from the STRIPPED text, but whose {@code startCharIndex}/{@code endCharIndex}
     * are mapped back to the ORIGINAL text via {@link StrippedMessage#mapSpanToOriginal}.
     */
    private MatchHighlight toHighlight(RawMatch raw, byte[] strippedBytes, String strippedText,
                                        StrippedMessage strippedMessage) {
        int startCharStripped = HyperscanScanService.byteOffsetToCharIndex(strippedBytes, raw.startByteOffset());
        int endCharStripped   = HyperscanScanService.byteOffsetToCharIndex(strippedBytes, raw.endByteOffset());

        // Guard against out-of-range indices (can happen with some UTF-16 surrogate pairs)
        startCharStripped = Math.max(0, Math.min(startCharStripped, strippedText.length()));
        endCharStripped   = Math.max(startCharStripped, Math.min(endCharStripped, strippedText.length()));

        String matchedText = strippedText.substring(startCharStripped, endCharStripped);

        int[] originalSpan = strippedMessage.mapSpanToOriginal(startCharStripped, endCharStripped);
        int originalStart = originalSpan[0];
        int originalEnd   = originalSpan[1];

        log.trace("Match: bytes [{},{}] -> stripped chars [{},{}] -> original chars [{},{}] = '{}'",
                raw.startByteOffset(), raw.endByteOffset(),
                startCharStripped, endCharStripped, originalStart, originalEnd, matchedText);

        return new MatchHighlight(originalStart, originalEnd, matchedText, false);
    }

    // ── Overlap merging ───────────────────────────────────────────────────────

    /**
     * Merges overlapping or adjacent {@link MatchHighlight} spans into a minimal
     * set of non-overlapping spans, sorted by start position.
     */
    private List<MatchHighlight> mergeOverlapping(List<MatchHighlight> highlights) {
        if (highlights.isEmpty()) return List.of();

        List<MatchHighlight> sorted = highlights.stream()
                .sorted(Comparator.comparingInt(MatchHighlight::startCharIndex))
                .collect(Collectors.toCollection(ArrayList::new));

        List<MatchHighlight> merged = new ArrayList<>();
        MatchHighlight current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            MatchHighlight next = sorted.get(i);
            if (next.startCharIndex() <= current.endCharIndex()) {
                // Overlapping or adjacent — extend the current span's POSITIONS
                // (start/end char indices, both in ORIGINAL-text coordinates —
                // this arithmetic is safe and unchanged).
                //
                // matchedText is NOT recomputed via position arithmetic here.
                // It previously was — via next.matchedText().substring(current.endCharIndex()
                // - next.startCharIndex()) — which mixes two different coordinate
                // systems: startCharIndex/endCharIndex are ORIGINAL-text positions,
                // but matchedText is a STRIPPED-text string (see toHighlight() and
                // class Javadoc). For a message with substantial HTML markup between
                // two matches, the original-text distance can vastly exceed the
                // stripped-text string's own length, so that substring's computed
                // start index could exceed next.matchedText().length() — throwing
                // StringIndexOutOfBoundsException ("Range [x, y) out of bounds for
                // length y") deep inside what looks like ordinary highlight merging,
                // exactly the failure reported against large HTML-heavy messages.
                //
                // This merged matchedText value is never actually read: buildHighlightedHtml
                // slices the rendered <mark> content directly from the ORIGINAL messageText
                // via startCharIndex/endCharIndex (see its own Javadoc), not from this field.
                // A safe, position-arithmetic-free concatenation is used instead — correct
                // is not required for a value nothing downstream consumes; not crashing is.
                int newEnd = Math.max(current.endCharIndex(), next.endCharIndex());
                String newText = current.matchedText().equals(next.matchedText())
                        ? current.matchedText()
                        : current.matchedText() + " " + next.matchedText();
                // A real (non-disclaimer) match anywhere in the merge wins: the merged
                // span is disclaimer-only only when EVERY constituent match was.
                boolean mergedInDisclaimer = current.inDisclaimer() && next.inDisclaimer();
                current = new MatchHighlight(current.startCharIndex(), newEnd, newText, mergedInDisclaimer);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * Tags a {@link MatchHighlight} with the term description that produced it —
     * used only by {@link #buildAnnotatedHighlightedHtml}.
     */
    private record TaggedHighlight(MatchHighlight h, String termDesc) {}

    /**
     * Merge step for the annotated variant — same overlap concept as
     * {@link #mergeOverlapping}, but currently a straight passthrough with NO
     * actual overlap merging (pre-existing limitation, not addressed by this
     * change; {@link #buildAnnotatedHighlightedHtml} is not on the active scan
     * pipeline's call path). Fixed here only to correct a genuine type
     * mismatch — the method previously declared {@code List<Object>} while
     * being used as {@code List<TaggedHighlight>}, which does not compile.
     */
    private List<TaggedHighlight> mergeTagged(List<TaggedHighlight> tagged) {
        return List.copyOf(tagged);
    }
}
