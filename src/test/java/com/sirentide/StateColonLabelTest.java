package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// State-diagram no-space colon labels — the sirentide/35 regression (the same
/// class Lattice caught for sequence at /33, which did not propagate to the new
/// state type). Pinned via the shared {@link com.sirentide.parse.DslParser}
/// peelLabel helper. The three spacing forms MEAN the same diagram, so they must
/// render byte-identically; the broken code split only on " : ", turning the
/// no-space forms into a phantom state named "B: go" with the label lost.
class StateColonLabelTest {

    @Test
    void allColonSpacingsAreTheSameDiagram() {
        String spaced   = Sirentide.render("state\nA --> B : go\nB --> [*]\n");
        String noBefore = Sirentide.render("state\nA --> B: go\nB --> [*]\n");
        String noAfter  = Sirentide.render("state\nA --> B :go\nB --> [*]\n");
        // the spaced form always worked; the others must now match it exactly
        assertEquals(spaced, noBefore, "'B: go' must render identically to 'B : go'");
        assertEquals(spaced, noAfter,  "'B :go' must render identically to 'B : go'");
        // and it's a real diagram (two state boxes), not the inert shell
        assertFalse(spaced.contains("width=\"120\""), "must not be the blank degrade shell");
        assertEquals(2, spaced.split("<rect", -1).length - 1, "two state boxes: A and B");
    }

    @Test
    void firstColonDelimitsSoAColonInsideTheLabelSurvives() {
        // only the FIRST colon peels; 'note: detail' stays one label, no crash
        String svg = Sirentide.render("state\nA --> B : note: detail\nB --> [*]\n");
        assertFalse(svg.contains("width=\"120\""), "colon-in-label must still render a real diagram");
    }
}
