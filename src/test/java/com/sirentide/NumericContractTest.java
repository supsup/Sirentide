package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Outcome;
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import com.sirentide.emit.SvgEmitter;
import com.sirentide.layout.AxisScale;
import com.sirentide.layout.LaidOut;
import com.sirentide.layout.Shape;
import com.sirentide.layout.Wedge;
import java.util.List;
import org.junit.jupiter.api.Test;

/// SIR-04 — the finite-geometry contract (independent audit, Medium correctness). A legal FINITE
/// numeric input can OVERFLOW an intermediate (an axis span, a running sum) to NaN/Infinity. Before
/// this fix the renderer degraded SILENTLY: the emitter's `fmt` clamped the non-finite value to 0 and
/// returned a WRONG-BUT-BOUNDED diagram as SUCCESS (no crash, no diagnostic) — violating DESIGN §6's
/// "loud, never silent" principle. This suite pins BOTH verified reproductions, asserting they now
/// degrade LOUDLY to the inert shell + a {@link Outcome#RENDER_BUG} diagnostic (the SAME mechanism a
/// genuine renderer defect takes — no new failure mode is invented), while legal in-magnitude input
/// stays a real success (byte-identity is the {@link GoldenSvgTest}/whole-suite authority).
///
/// RED-FIRST (verified on the pre-fix tip `c179eaa` by reverting the three guards):
///   - {@code new AxisScale(-1e308, 1e308)} CONSTRUCTED OK; `span()` == Infinity; `project(1e308,0,100)`
///     == NaN (silent).
///   - pie two-`1e308` slices → NON-inert SVG (len 2332), outcome OK, wedges collapsed to `M 120 120
///     L 120 20 A 100 100 0 0 1 120 20 Z` (a visually BLANK zero-sweep wedge) returned as success.
///   - xychart default (grouped bars) `-1e308`/`1e308` → NON-inert SVG (len 48028), outcome OK.
///   - a hand-built scene with a NaN wedge coordinate → `SvgEmitter.emit` returned a string with the
///     NaN silently rendered as `0`.
/// Every assertion below inverts one of those to the post-fix LOUD outcome.
class NumericContractTest {

    /// The inert empty shell — the universal degrade target (kept in sync with {@link Sirentide}'s
    /// package-private constant, which this cross-package test cannot import; mirrors FuzzInvariantTest).
    private static final String INERT_SHELL =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"0\" height=\"0\" viewBox=\"0 0 0 0\"></svg>";

    // ---------------------------------------------------------------------------------------------
    // Guard 2 — AxisScale span overflow (the exact gap the finding names: endpoints guarded, span not)
    // ---------------------------------------------------------------------------------------------

    /// A domain whose ENDPOINTS are finite but whose SPAN overflows to +Infinity must be rejected
    /// LOUDLY at construction — not accepted into a scale that then silently collapses every
    /// projection. RED-on-pre-fix: the constructor accepted it (span()==Infinity, project(1e308,…)==NaN).
    @Test
    void axisScaleSpanOverflowIsRejectedLoudly() {
        // Both endpoints are legal finite doubles — the pre-existing endpoint guard does NOT fire.
        assertTrue(Double.isFinite(-1e308) && Double.isFinite(1e308),
            "both endpoints are finite — only the DERIVED span overflows");
        assertThrows(IllegalArgumentException.class, () -> new AxisScale(-1e308, 1e308),
            "an overflowing span [-1e308, 1e308] must be rejected, not silently collapsed");
        // AxisScale.of over the same finite-but-huge values likewise degrades loud (it builds the
        // constructor from finite ends whose span overflows).
        assertThrows(IllegalArgumentException.class, () -> AxisScale.of(-1e308, 1e308));
    }

    /// In-magnitude domains are UNTOUCHED — the guard fires only on the overflow, so legal axes keep
    /// projecting exactly as before (the byte-identity floor for the axis diagrams).
    @Test
    void inRangeAxisIsUnaffected() {
        AxisScale a = new AxisScale(-1000, 1000);
        assertEquals(2000, a.span(), 0.0);
        assertEquals(50, a.project(0, 0, 100), 1e-9);   // proportional, finite, unchanged
        // A large-but-non-overflowing span is fine (1e307 - (-1e307) = 2e307, still finite).
        AxisScale big = new AxisScale(-1e307, 1e307);
        assertTrue(Double.isFinite(big.span()), "a finite span, however large, is legal");
    }

    // ---------------------------------------------------------------------------------------------
    // Guard 1 — the emit-time WAIST: a non-finite coordinate is refused, not clamped to 0
    // ---------------------------------------------------------------------------------------------

    /// The class-level catch: ANY non-finite coordinate that reaches the emitter (from ANY diagram's
    /// overflow) is now REFUSED — not silently formatted as `0`. Exercised directly through the `fmt`
    /// waist by emitting a scene whose wedge carries a NaN centre. RED-on-pre-fix: `emit` returned a
    /// well-formed SVG with the NaN rendered as `0`.
    @Test
    void nonFiniteCoordinateIsRefusedAtTheEmitWaist() {
        LaidOut nanScene = new LaidOut(100, 100,
            List.<Shape>of(new Wedge(Double.NaN, 10, 20, 0, 1, "#000000")));
        assertThrows(IllegalStateException.class, () -> SvgEmitter.emit(nanScene),
            "a NaN coordinate must be refused at emit, not clamped to 0");

        LaidOut infScene = new LaidOut(100, 100,
            List.<Shape>of(new Wedge(10, Double.POSITIVE_INFINITY, 20, 0, 1, "#000000")));
        assertThrows(IllegalStateException.class, () -> SvgEmitter.emit(infScene),
            "an Infinity coordinate must be refused at emit, not clamped to 0");

        // A wholly-finite scene still emits normally — the waist bites ONLY on non-finite (in-range
        // output is unchanged; the byte-identity floor for the emitter).
        LaidOut finite = new LaidOut(100, 100,
            List.<Shape>of(new Wedge(50, 50, 20, 0, 1, "#000000")));
        String svg = SvgEmitter.emit(finite);
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>") && svg.contains("<path"),
            "a finite scene emits real geometry unchanged: " + svg);
    }

    // ---------------------------------------------------------------------------------------------
    // Guard 3 — Pie total overflow (end-to-end LOUD degrade; the emit waist cannot see this one)
    // ---------------------------------------------------------------------------------------------

    /// The named pie reproduction: two `1e308` slices overflow `positiveTotal()` to +Infinity, so every
    /// sweep is `value / Infinity == 0` — a FINITE, blank zero-angle pie the emitter accepts. This
    /// class of defect is INVISIBLE at the emit waist (the coordinates are finite), so it must be — and
    /// now is — caught at the aggregation point. RED-on-pre-fix: a non-inert (len 2332) blank pie,
    /// outcome OK. GREEN: the inert shell + RENDER_BUG.
    @Test
    void pieMagnitudeOverflowDegradesLoudlyNotSilentBlank() {
        String dsl = "pie\n  \"A\" : 1e308\n  \"B\" : 1e308\n";
        String svg = Sirentide.render(dsl);
        assertEquals(INERT_SHELL, svg, "an overflowing pie total must degrade to the inert shell");

        RenderResult rr = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(svg, rr.svg(), "diagnostics svg stays byte-identical to render");
        assertEquals(Outcome.RENDER_BUG, rr.diagnostics().outcome(),
            "a finite-input overflow is a renderer-defect-class LOUD degrade, not OK");
        assertFalse(rr.diagnostics().outcome() == Outcome.OK,
            "the silently-blank-but-success outcome is exactly what SIR-04 eliminates");
    }

    /// The end-to-end AxisScale-span reproduction: a default (grouped-bars) xychart with a cross-zero
    /// `-1e308`/`1e308` domain builds `AxisScale(-1e308, 1e308)` from finite endpoints whose span
    /// overflows. RED-on-pre-fix: a non-inert (len 48028) silently-wrong chart, outcome OK. GREEN: the
    /// inert shell + RENDER_BUG (the AxisScale constructor throw propagates through layout to the bake
    /// guard).
    @Test
    void xychartAxisSpanOverflowDegradesLoudly() {
        String dsl = "xychart\n  \"A\" : -1e308\n  \"B\" : 1e308\n";
        String svg = Sirentide.render(dsl);
        assertEquals(INERT_SHELL, svg, "an overflowing xychart axis span must degrade to the inert shell");

        RenderResult rr = Sirentide.renderWithDiagnostics(dsl);
        assertEquals(svg, rr.svg(), "diagnostics svg stays byte-identical to render");
        assertEquals(Outcome.RENDER_BUG, rr.diagnostics().outcome(),
            "a finite-input axis-span overflow is a LOUD renderer-defect-class degrade, not OK");
    }

    // ---------------------------------------------------------------------------------------------
    // Byte-identity floor: legal in-magnitude input still succeeds (only overflow changed behavior)
    // ---------------------------------------------------------------------------------------------

    /// A normal pie and a normal xychart still render REAL content with outcome OK — the three guards
    /// fire ONLY on non-finite overflow, so every legal in-magnitude input is unchanged. (The exact
    /// byte-identity of legal output is pinned by {@link GoldenSvgTest} and the whole suite.)
    @Test
    void inMagnitudeInputStillSucceeds() {
        String pie = Sirentide.render("pie\n  \"A\" : 40\n  \"B\" : 60\n");
        assertNotEquals(INERT_SHELL, pie, "a normal pie is real content, not the inert shell");
        assertEquals(Outcome.OK,
            Sirentide.renderWithDiagnostics("pie\n  \"A\" : 40\n  \"B\" : 60\n").diagnostics().outcome());

        String xy = Sirentide.render("xychart\n  \"A\" : 3\n  \"B\" : 8\n  \"C\" : 5\n");
        assertNotEquals(INERT_SHELL, xy, "a normal xychart is real content, not the inert shell");
        assertEquals(Outcome.OK,
            Sirentide.renderWithDiagnostics("xychart\n  \"A\" : 3\n  \"B\" : 8\n  \"C\" : 5\n")
                .diagnostics().outcome());
    }
}
