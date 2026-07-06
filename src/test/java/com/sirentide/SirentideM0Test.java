package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// M0 scaffold smoke: the parse→IR→layout→emit pipeline runs end to end and produces a
/// well-formed, contract-clean SVG shell. Real per-type rendering + the full containment
/// harness (left/right-containment, wrapper-delta) land in M1 against Confluence's
/// sirentide-output-contract.
class SirentideM0Test {

    @Test
    void emptyDiagramBakesToContractCleanSvgShell() {
        String svg = Sirentide.render("");

        assertTrue(svg.startsWith("<svg"), "emits an <svg> root");
        assertTrue(svg.contains("viewBox="), "has a viewBox");
        assertTrue(svg.endsWith("</svg>"), "well-formed");
    }

    // The old denylist smoke (no script/style/foreignObject/on*) is superseded by the ALLOWLIST
    // guard in ContainmentTest, which parses the SVG and enforces element+attribute+value bounds
    // across the whole corpus — a real containment test, not a leading-space heuristic.

    @Test
    void numberFormattingIsDeterministicIntegerWhenWhole() {
        // Byte-identical bakes (docs/DESIGN.md §6): a whole dimension emits as an integer,
        // not "0.0", and no locale dependence.
        String svg = Sirentide.render("");
        assertTrue(svg.contains("viewBox=\"0 0 0 0\""), "whole numbers render without decimals");
    }

    @Test
    void oversizedInputDegradesToInertShellWithoutOom() {
        // A 10M-row-equivalent input (well over the 1MB source cap) must degrade to the inert
        // empty shell — never OOM parsing it into millions of shapes, never throw (DESIGN §6/§7).
        StringBuilder big = new StringBuilder("pie\n");
        String row = "  \"x\" : 1\n";
        while (big.length() < 2_000_000) {   // ~2 MB, ~200k rows, past MAX_SOURCE_BYTES
            big.append(row);
        }
        String svg = Sirentide.render(big.toString());
        assertEquals(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"></svg>",
            svg, "oversized input degrades to the inert shell");
    }

    @Test
    void nonFiniteInputDoesNotEmitInfinity() {
        // 1e400 parses to Infinity in Java WITHOUT throwing; it must be rejected at parse so no
        // x="Infinity" ever reaches the output (the emitter clamp is the second line of defense).
        String svg = Sirentide.render("pie\n  \"Overflow\" : 1e400\n");
        assertTrue(!svg.contains("Infinity"), "no Infinity literal leaks into output: " + svg);
    }
}
