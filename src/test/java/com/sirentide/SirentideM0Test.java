package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void outputCarriesNoExecutableSurface() {
        // The trust story starts at the scaffold: even the empty shell must be inert
        // (docs/DESIGN.md §5) — no script/style/foreignObject/event handlers.
        String svg = Sirentide.render("anything");

        assertFalse(svg.contains("<script"), "no script");
        assertFalse(svg.contains("<style"), "no style");
        assertFalse(svg.contains("foreignObject"), "no foreignObject");
        assertFalse(svg.contains(" on"), "no on* handlers");
    }

    @Test
    void numberFormattingIsDeterministicIntegerWhenWhole() {
        // Byte-identical bakes (docs/DESIGN.md §6): a whole dimension emits as an integer,
        // not "0.0", and no locale dependence.
        String svg = Sirentide.render("");
        assertTrue(svg.contains("viewBox=\"0 0 0 0\""), "whole numbers render without decimals");
    }
}
