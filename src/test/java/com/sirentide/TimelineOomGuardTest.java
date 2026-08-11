package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Outcome;
import com.sirentide.api.Sirentide;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.font.FontMetrics;
import com.sirentide.ir.Timeline;
import com.sirentide.layout.GlyphRun;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Shape;
import com.sirentide.layout.TimelineLayout;
import com.sirentide.parse.DslParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// H2 regression guard (deep-review sirentide/14, plan 8ec4fa52). A *legal* timeline DSL —
/// MAX_DATA_ROWS rows each with a MAX_LABEL_LEN (512-char) label — previously built ~5.9 GB of
/// glyph-path data (TimelineLayout was the only layout that did NOT ellipsize its labels) and OOM'd
/// before the 5 MB output cap was ever checked. These tests pin: (1) the pathological case now
/// returns fast and bounded, (2) the timeline actually ellipsizes (mutation-proof for T1), and
/// (3) the emitter's incremental byte-cap throws a RuntimeException that degrades to the inert shell.
class TimelineOomGuardTest {

    /// Mirror of TimelineLayout's internal caps — used only to independently recompute the expected
    /// ellipsized label for the non-vacuity test below.
    private static final double MAX_LABEL_W = 120;
    private static final double TOP_SIZE = 11;

    private static final int MAX_DATA_ROWS = 10_000;
    private static final int MAX_LABEL_LEN = 512;
    private static final int MAX_OUTPUT_BYTES = 5_000_000;

    /// Drop the a11y `<title>…</title><desc>…</desc>` block — this test compares LAYOUT geometry,
    /// and the desc names the raw (char-capped) label, which differs from the width-ellipsized
    /// visible glyph run by construction.
    private static String stripA11y(String svg) {
        int t = svg.indexOf("<title>");
        int d = svg.indexOf("</desc>");
        if (t >= 0 && d > t) {
            return svg.substring(0, t) + svg.substring(d + "</desc>".length());
        }
        return svg;
    }

    /// THE regression proof: a timeline near the parse caps renders fast, doesn't throw/hang, and
    /// produces bounded output. Before the fix this allocated ~5.9 GB and took ~4.5 s (or OOM'd);
    /// with per-label ellipsization + the emitter's incremental cap it is bounded and quick.
    @Test
    void pathologicalTimelineIsBoundedAndFast() {
        StringBuilder dsl = new StringBuilder("timeline\n");
        String longLabel = "W".repeat(MAX_LABEL_LEN);   // 512-char label — the worst legal case
        for (int i = 0; i < MAX_DATA_ROWS; i++) {
            dsl.append("  \"").append(longLabel).append("\" : ").append(2000 + i).append('\n');
        }
        String[] svg = new String[1];
        assertTimeoutPreemptively(Duration.ofSeconds(30),
            () -> svg[0] = Sirentide.render(dsl.toString()));
        assertTrue(svg[0].startsWith("<svg"), "well-formed SVG (real or inert shell)");
        assertTrue(svg[0].length() <= MAX_OUTPUT_BYTES,
            "output bounded to MAX_OUTPUT_BYTES, was " + svg[0].length());
    }

    /// The interval-packing worst row count, not merely the worst label width: all 10,000 legal
    /// events project to one x and therefore need 10,000 top rows + 10,000 value rows. Layout retains
    /// only linear interval/row state and numeric canvas growth; emission either succeeds under the
    /// existing whole-render cap or degrades to the inert shell, but it never silently stacks or OOMs.
    @Test
    void parserScaleCoincidentRowsStayBoundedByTheExistingOutputGuard() {
        StringBuilder dsl = new StringBuilder("timeline\n");
        for (int i = 0; i < MAX_DATA_ROWS; i++) {
            dsl.append("  \"x\" : 7\n");
        }
        String[] svg = new String[1];
        assertTimeoutPreemptively(Duration.ofSeconds(30),
            () -> svg[0] = Sirentide.render(dsl.toString()));
        assertTrue(svg[0].startsWith("<svg") && svg[0].endsWith("</svg>"),
            "worst-row result is still a complete SVG (real or inert)");
        assertTrue(svg[0].length() <= MAX_OUTPUT_BYTES,
            "worst-row output is bounded to MAX_OUTPUT_BYTES, was " + svg[0].length());
    }

    /// A renderer miss degrades `$…$` to the raw source. That path intentionally skips Timeline's
    /// ordinary ellipsis, so enough legal 512-character formulas could otherwise retain many large
    /// glyph paths during layout, before SvgEmitter gets a chance to enforce its output cap. The
    /// layout's named materialization budget must stop that work and use the existing typed cap
    /// classification rather than misreporting a renderer bug.
    @Test
    void mathFallbackTripsTheNamedLayoutBudgetBeforeUnsafeGlyphRetention() {
        String rawMath = "$" + "W".repeat(MAX_LABEL_LEN - 2) + "$";
        StringBuilder dsl = new StringBuilder("timeline\n");
        for (int i = 0; i < 16; i++) {
            dsl.append("  \"").append(rawMath).append("\" : 7\n");
        }
        Timeline timeline = (Timeline) DslParser.parse(dsl.toString());
        MathFragmentRenderer unavailable = (latex, size) -> java.util.Optional.empty();

        IllegalStateException direct = assertThrows(IllegalStateException.class,
            () -> TimelineLayout.layout(timeline, unavailable));
        assertTrue(direct.getMessage().contains("MAX_LAYOUT_WORK"));
        assertTrue(direct.getMessage().contains("MAX_TIMELINE_LABEL_WORK"));

        var diagnosed = Sirentide.renderWithDiagnostics(dsl.toString(), unavailable);
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, diagnosed.diagnostics().outcome());
        assertEquals("layout", diagnosed.diagnostics().stage());
        assertTrue(diagnosed.diagnostics().detail().contains("MAX_TIMELINE_LABEL_WORK"));
        assertEquals(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"></svg>",
            diagnosed.svg());
    }

    /// Non-vacuity for T1: the timeline MUST actually call FONT.ellipsize on its event labels.
    /// A diagram whose label is a long run is rendered, and independently a diagram whose label is
    /// the PRE-ellipsized form (computed here) is rendered; the two SVGs must be byte-identical —
    /// which can only hold if the layout ellipsizes. Remove the ellipsize call and render(longLabel)
    /// keeps all ~200 glyphs, diverging from the truncated form → this assertion FAILS.
    @Test
    void timelineEllipsizesLongLabel() {
        String longLabel = "W".repeat(200);
        String expected = FontMetrics.bundled().ellipsize(longLabel, MAX_LABEL_W, TOP_SIZE);
        // Sanity: the label genuinely overflows, so ellipsize actually truncates (ends with the
        // ellipsis char and is much shorter than the raw label).
        assertTrue(expected.endsWith("…"), "test label must overflow → ellipsized form");
        assertTrue(expected.length() < longLabel.length(), "ellipsized form is shorter");

        String rawSvg = Sirentide.render("timeline\n  \"" + longLabel + "\" : 2020\n");
        String truncSvg = Sirentide.render("timeline\n  \"" + expected + "\" : 2020\n");
        // Compare GEOMETRY only: the a11y <desc> AND the semantic-anchor `<g data-sirentide-id>`
        // (sanitized from the RAW label) both name the un-ellipsized label, so both differ from the
        // width-ellipsized VISIBLE form by construction — this test is about the LAYOUT ellipsization
        // of the glyph run, so strip the a11y block and the additive anchor wrappers from both.
        assertEquals(stripAnchors(stripA11y(truncSvg)), stripAnchors(stripA11y(rawSvg)),
            "timeline must ellipsize its event label (else the long-label render diverges)");
    }

    /// Strip the additive semantic-anchor `<g data-sirentide-*>`/`</g>` wrappers so a pure-geometry
    /// comparison ignores the anchor id (sanitized from the raw label → differs by construction).
    private static String stripAnchors(String svg) {
        return svg.replaceAll("<g data-sirentide-[^>]*>", "").replace("</g>", "");
    }

    /// The emitter's incremental byte-cap throws a plain RuntimeException (IllegalStateException)
    /// once the accumulating buffer passes MAX_OUTPUT_BYTES — this is the defense-in-depth that
    /// bounds output regardless of how many shapes a layout produces.
    @Test
    void emitterThrowsWhenOutputExceedsCap() {
        List<Shape> shapes = new ArrayList<>();
        String bigPath = "M0 0 ".repeat(200_000);       // ~1 MB of path data per shape
        for (int i = 0; i < 8; i++) {                    // ~8 MB total → over the 5 MB cap
            shapes.add(new GlyphRun(bigPath, "#000000"));
        }
        LaidOut oversized = new LaidOut(10, 10, shapes);
        RuntimeException ex = assertThrows(IllegalStateException.class,
            () -> SvgEmitter.emit(oversized));
        assertTrue(ex.getMessage().contains("MAX_OUTPUT_BYTES"),
            "cap breach names the constant, was: " + ex.getMessage());
    }

    /// The emitter's RuntimeException path is caught by render → the bake degrades to the inert
    /// shell (the "never throw the bake" invariant survives for RuntimeException even as OOM is now
    /// deliberately un-caught). A pie of many thin slices with 512-char labels drives the emitter
    /// over the cap; render must still return a well-formed inert shell, not throw.
    @Test
    void overCapRenderDegradesToInertShell() {
        StringBuilder dsl = new StringBuilder("pie\n");
        String longLabel = "Z".repeat(MAX_LABEL_LEN);
        for (int i = 0; i < MAX_DATA_ROWS; i++) {
            dsl.append("  \"").append(longLabel).append("\" : 1\n");   // all-equal → thin slices
        }
        String svg = Sirentide.render(dsl.toString());
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertTrue(svg.length() <= MAX_OUTPUT_BYTES, "bounded, was " + svg.length());
    }
}
