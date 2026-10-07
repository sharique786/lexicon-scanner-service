package com.db.macs3.ecomms.spectre.model;

import com.db.macs3.ecomms.spectre.model.ResolvedPatternTree.AndNot;
import com.db.macs3.ecomms.spectre.model.ResolvedPatternTree.Chain;
import com.db.macs3.ecomms.spectre.model.ResolvedPatternTree.TermMetadataParseException;
import com.db.macs3.ecomms.spectre.service.ResolvedPatternMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ResolvedPatternTree.build - per-side pairing of resolvedPatterns with pattern lists")
class ResolvedPatternTreeTest {

    private final ResolvedPatternMatcher matcher = new ResolvedPatternMatcher();

    @Test
    @DisplayName("decomposed leaves (count == shape) are zipped and keep operator + distance")
    void decomposedLeaves_zipped() {
        ResolvedPatternTree t = ResolvedPatternTree.build("t",
                "\\bbash\\b FOLLOWEDBY{3} \\bfuck\\b", List.of("\\bbash\\b", "\\bfuck\\b"), null);
        Chain c = (Chain) t;
        assertThat(c.leaves()).hasSize(2);
        assertThat(c.operators()).containsExactly("FOLLOWEDBY");
        assertThat(c.distances()).containsExactly(3);
        assertThat(matcher.findChainMatches(c, "bash a b c d fuck")).isEmpty();
        assertThat(matcher.findChainMatches(c, "bash a fuck")).hasSize(1);
    }

    @Test
    @DisplayName("ONE self-contained pattern with an embedded gap, although resolvedPatterns shows FOLLOWEDBY - single leaf")
    void singleSelfContainedPattern_collapsesToOneLeaf() {
        String embedded = "\\bbash\\b(?:\\s+\\S+){0,2}\\s+\\bfuck\\b";
        ResolvedPatternTree t = ResolvedPatternTree.build("t",
                "\\bbash\\b FOLLOWEDBY{2} \\bfuck\\b", List.of(embedded), null);
        Chain c = (Chain) t;
        assertThat(c.leaves()).hasSize(1);
        assertThat(c.operators()).isEmpty();
        assertThat(matcher.findChainMatches(c, "x bash a b fuck y"))
                .extracting(ResolvedPatternMatcher.TextSpan::matchedText)
                .containsExactly("bash a b fuck");
        assertThat(matcher.findChainMatches(c, "bash a b c fuck")).isEmpty();
    }

    @Test
    @DisplayName("AND NOT: required decomposed (2 leaves) + excluded a single pattern - sides resolved independently")
    void andNot_sidesResolvedIndependently() {
        ResolvedPatternTree t = ResolvedPatternTree.build("t",
                "\\ba\\b NEAR{2} \\bb\\b AND NOT (\\bx\\b FOLLOWEDBY{1} \\by\\b)",
                List.of("\\ba\\b", "\\bb\\b"), List.of("\\bx\\b(?:\\s+\\S+){0,1}\\s+\\by\\b"));
        AndNot an = (AndNot) t;
        assertThat(((Chain) an.required()).leaves()).hasSize(2);
        assertThat(((Chain) an.excluded()).leaves()).hasSize(1);
    }

    @Test
    @DisplayName("pattern count that is neither the shape's leaf count nor 1 is a structural error")
    void countMismatch_throws() {
        assertThatThrownBy(() -> ResolvedPatternTree.build("t",
                "\\ba\\b NEAR{1} \\bb\\b NEAR{1} \\bc\\b", List.of("\\ba\\b", "\\bb\\b"), null))
                .isInstanceOf(TermMetadataParseException.class);
    }

    @Test
    @DisplayName("an AND NOT shape without exclusionRegex is a structural error")
    void andNotWithoutExclusion_throws() {
        assertThatThrownBy(() -> ResolvedPatternTree.build("t",
                "\\ba\\b AND NOT (\\bb\\b)", List.of("\\ba\\b"), null))
                .isInstanceOf(TermMetadataParseException.class);
    }

    @Test
    @DisplayName("a pattern Java cannot compile surfaces as TermMetadataParseException")
    void invalidJavaRegex_wrapped() {
        assertThatThrownBy(() -> ResolvedPatternTree.build("t", "(a", List.of("(a"), null))
                .isInstanceOf(TermMetadataParseException.class);
    }
}
