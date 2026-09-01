package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.ResolvedPatternTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a {@link ResolvedPatternTree} against real message text using
 * plain {@code java.util.regex} — the only way to genuinely verify a NEAR/
 * FOLLOWEDBY/AND-NOT condition for a decomposed term, since the Compile
 * Service's {@code regexPattern}/{@code exclusionRegex} leaves are pure,
 * gap-free, boolean-AND-only fragments with no order/distance information
 * of their own (see {@link ResolvedPatternTree} class Javadoc).
 *
 * <h2>Ported from {@code lexicon-scan-engine}'s {@code ResolvedPatternAreaEvaluator}</h2>
 * <p>Same word-span/backtracking algorithm (leaves are matched via
 * {@code Matcher.find()} against the real text, occurrences mapped to word
 * indices via {@code \S+} word spans, NEAR allows either direction while
 * FOLLOWEDBY requires strictly increasing indices, gap = whole words
 * strictly between two chosen indices), adapted from "per scanned area" (the
 * Scan Engine may have a subject/body/attachment split) to this project's
 * single-message-at-a-time model — every call here evaluates ONE message's
 * stripped text, never merged across messages. Enumerates EVERY satisfying
 * leaf-occurrence combination (not just the first), so a proximity term
 * reports the same "every genuine occurrence, individually highlighted"
 * behavior this project's other match highlighting already provides.
 *
 * <h2>Why this replaced Hyperscan for Natural Language terms entirely</h2>
 * <p>Natural Language terms previously scanned via native Hyperscan
 * {@code HS_FLAG_COMBINATION} — confirmed unreliable for AND NOT by
 * Hyperscan's own documented eager, progressive combination evaluation
 * (see {@link com.db.macs3.ecomms.spectre.model.Models.CompiledTerm} class
 * Javadoc) — and never had real proximity-distance verification for
 * decomposed NEAR/FOLLOWEDBY terms at all (decomposed leaves were combined
 * with pure boolean AND, discarding the configured distance). Evaluating
 * every PASS Natural Language term through this class instead fixes both at
 * once: no native {@code COMBINATION} is built anywhere any more, and every
 * NEAR/FOLLOWEDBY distance/order constraint the analyst configured is
 * actually checked. See {@link LexiconScanOrchestrator} class Javadoc.
 */
@Service
public class ResolvedPatternMatcher {

    private static final Logger log = LoggerFactory.getLogger(ResolvedPatternMatcher.class);

    /** Output cap: at most this many distinct occurrence spans reported per (term, message). */
    private static final int MAX_HITS = 200;

    /**
     * Internal safety bound on backtracking work — deliberately much larger
     * than {@link #MAX_HITS}, so a pathological leaf-occurrence count
     * degrades by truncation, logged once, rather than by hanging.
     */
    private static final int MAX_BACKTRACK_VISITS = 200_000;

    /**
     * One matched occurrence in the message's own coordinate space (whatever
     * text {@link #findChainMatches} was called against — this project
     * always calls it with the HTML-stripped text, mapping the result back
     * to original-text coordinates afterward — see
     * {@link LexiconScanOrchestrator}).
     *
     * @param startChar   start of the matched span, inclusive
     * @param endChar     end of the matched span, exclusive
     * @param matchedText the substring of the input text this span covers
     */
    public record TextSpan(int startChar, int endChar, String matchedText) {}

    /**
     * Evaluates a {@link ResolvedPatternTree.Chain} (a plain, possibly
     * length-1, NEAR/FOLLOWEDBY sequence — never an {@code AndNot}; callers
     * evaluate an AND NOT tree's required/excluded sides independently via
     * this same method so they can preserve the "excluded vs. never
     * matched" distinction — see {@link LexiconScanOrchestrator}) against
     * one message's text.
     *
     * @param chain the chain to evaluate
     * @param text  the message text to search — this project always passes
     *              the HTML-stripped text, consistent with disclaimer
     *              detection and every other match-finding path
     * @return every satisfying occurrence found, capped at {@link #MAX_HITS} and
     *         deduplicated by resulting span — empty if the chain's leaves don't
     *         all appear, or don't satisfy the proximity constraints, anywhere in this text
     */
    public List<TextSpan> findChainMatches(ResolvedPatternTree.Chain chain, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<int[]> words = wordSpans(text);
        List<List<LeafOccurrence>> occurrencesPerLeaf = new ArrayList<>(chain.leaves().size());
        for (Pattern leaf : chain.leaves()) {
            List<LeafOccurrence> occurrences = findOccurrences(leaf, text, words);
            if (occurrences.isEmpty()) {
                return List.of(); // this leaf never appears at all — the whole chain cannot match here
            }
            occurrencesPerLeaf.add(occurrences);
        }

        Set<TextSpan> collected = new LinkedHashSet<>();
        int[] visits = {0};
        backtrack(occurrencesPerLeaf, chain.operators(), chain.distances(), 0, null,
                new LeafOccurrence[occurrencesPerLeaf.size()], text, collected, visits);
        if (visits[0] > MAX_BACKTRACK_VISITS) {
            log.debug("resolved-pattern chain evaluation truncated after {} backtracking visits — "
                    + "reporting {} occurrence(s) found so far", visits[0], collected.size());
        }
        return new ArrayList<>(collected);
    }

    private void backtrack(List<List<LeafOccurrence>> occurrencesPerLeaf, List<String> operators,
                            List<Integer> distances, int leafIndex, LeafOccurrence previous,
                            LeafOccurrence[] chosen, String text, Set<TextSpan> collected, int[] visits) {
        if (collected.size() >= MAX_HITS || visits[0]++ > MAX_BACKTRACK_VISITS) {
            return;
        }
        if (leafIndex == occurrencesPerLeaf.size()) {
            int start = chosen[0].startChar();
            int end = chosen[0].endChar();
            for (LeafOccurrence o : chosen) {
                start = Math.min(start, o.startChar());
                end = Math.max(end, o.endChar());
            }
            collected.add(new TextSpan(start, end, text.substring(start, end)));
            return;
        }
        for (LeafOccurrence candidate : occurrencesPerLeaf.get(leafIndex)) {
            chosen[leafIndex] = candidate;
            if (leafIndex == 0) {
                backtrack(occurrencesPerLeaf, operators, distances, leafIndex + 1, candidate, chosen,
                        text, collected, visits);
                continue;
            }
            String operator = operators.get(leafIndex - 1);
            int maxGap = distances.get(leafIndex - 1);
            boolean directionOk = "NEAR".equals(operator) || candidate.endWordIndex() > previous.endWordIndex();
            int gap = Math.abs(candidate.endWordIndex() - previous.endWordIndex()) - 1;
            if (directionOk && gap >= 0 && gap <= maxGap) {
                backtrack(occurrencesPerLeaf, operators, distances, leafIndex + 1, candidate, chosen,
                        text, collected, visits);
            }
        }
    }

    /**
     * Every occurrence of {@code leaf} in {@code text}, carrying both its
     * full character span and the word index its END falls in (for the same
     * gap-counting definition {@code NEAR{n}}/{@code FOLLOWEDBY{n}} use
     * elsewhere on this platform).
     */
    private List<LeafOccurrence> findOccurrences(Pattern leaf, String text, List<int[]> words) {
        List<LeafOccurrence> occurrences = new ArrayList<>();
        Matcher m = leaf.matcher(text);
        while (m.find()) {
            int endWordIndex = wordIndexAtOrBefore(words, m.end());
            if (endWordIndex >= 0) {
                occurrences.add(new LeafOccurrence(m.start(), m.end(), endWordIndex));
            }
            if (m.end() == m.start()) {
                break; // guard against a zero-width match looping forever
            }
        }
        return occurrences;
    }

    private List<int[]> wordSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = Pattern.compile("\\S+").matcher(text);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        return spans;
    }

    private int wordIndexAtOrBefore(List<int[]> words, int charOffset) {
        for (int i = words.size() - 1; i >= 0; i--) {
            if (words.get(i)[0] < charOffset) {
                return i;
            }
        }
        return -1;
    }

    private record LeafOccurrence(int startChar, int endChar, int endWordIndex) {}
}
