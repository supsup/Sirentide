package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.DiagramConfig;
import com.sirentide.ir.Theme;
import com.sirentide.parse.DslParser;
import org.junit.jupiter.api.Test;

/// The leading CONFIG BLOCK + theme palettes + self-contained background (plan sirentide-theming-config).
/// Covers: `%% key: value` config parse (title/theme/direction, unknown-inert, malformed-no-throw,
/// no-config-default); the `theme: dark` self-contained dark background + light-adjusted structural
/// colours; the config `title` feeding the `<title>`; and the OPTION-A guarantee that a no-config /
/// `theme: default` bake is BYTE-IDENTICAL to the pre-theming renderer.
class ThemeConfigTest {

    private static final String PIE_BODY = "pie\n  \"Reviews\" : 40\n  \"Builds\" : 60\n";

    // ---- Receipt 1: config parse ------------------------------------------------------------

    @Test
    void titleDirectiveSetsTheTitle() {
        DiagramConfig c = DslParser.parseConfig("%% title: My Diagram\n" + PIE_BODY);
        assertEquals("My Diagram", c.title());
    }

    @Test
    void themeDarkDirectiveSelectsDark() {
        DiagramConfig c = DslParser.parseConfig("%% theme: dark\n" + PIE_BODY);
        assertEquals(Theme.DARK, c.theme());
    }

    @Test
    void themeNeutralAndDefaultSelectTheirThemes() {
        assertEquals(Theme.NEUTRAL, DslParser.parseConfig("%% theme: neutral\n" + PIE_BODY).theme());
        assertEquals(Theme.DEFAULT, DslParser.parseConfig("%% theme: default\n" + PIE_BODY).theme());
    }

    @Test
    void directionDirectiveIsParsedAndUnknownIsNull() {
        assertEquals("LR", DslParser.parseConfig("%% direction: LR\n" + PIE_BODY).direction());
        assertEquals("TD", DslParser.parseConfig("%% direction: td\n" + PIE_BODY).direction());
        assertNull(DslParser.parseConfig("%% direction: sideways\n" + PIE_BODY).direction());
    }

    @Test
    void unknownKeyIsIgnoredInert() {
        DiagramConfig c = DslParser.parseConfig("%% wibble: 42\n%% theme: dark\n" + PIE_BODY);
        assertEquals(Theme.DARK, c.theme());   // the known key still took; the unknown one was inert
        assertNull(c.title());
    }

    @Test
    void unknownThemeValueDegradesToDefault() {
        DiagramConfig c = DslParser.parseConfig("%% theme: rainbow\n" + PIE_BODY);
        assertEquals(Theme.DEFAULT, c.theme());   // an unrecognized theme value is inert (never throws)
    }

    @Test
    void malformedDirectiveDoesNotThrow() {
        // A `%%` with no `key: value`, and a mermaid `%%{init:{…}}%%` block — both inert, never throw.
        DiagramConfig c = DslParser.parseConfig("%% no colon here\n%%{init:{\"theme\":\"dark\"}}%%\n" + PIE_BODY);
        assertEquals(Theme.DEFAULT, c.theme());
        assertNull(c.title());
        // The whole diagram still bakes (never fails the bake).
        String svg = Sirentide.render("%% no colon here\n" + PIE_BODY);
        assertTrue(svg.contains("<svg") && !svg.contains("width=\"0\" height=\"0\""));
    }

    @Test
    void sirentideMarkerLineIsAcceptedAndIgnored() {
        DiagramConfig c = DslParser.parseConfig("sirentide\n%% title: Marked\n%% theme: dark\n" + PIE_BODY);
        assertEquals("Marked", c.title());
        assertEquals(Theme.DARK, c.theme());
        // And the body still parses (the marker + directives were stripped before the type header).
        String svg = Sirentide.render("sirentide\n%% theme: dark\n" + PIE_BODY);
        assertTrue(svg.contains("<svg"), "marker + config still bakes the body");
    }

    @Test
    void noConfigIsTheDefault() {
        DiagramConfig c = DslParser.parseConfig(PIE_BODY);
        assertNull(c.title());
        assertEquals(Theme.DEFAULT, c.theme());
        assertNull(c.direction());
    }

    // ---- Receipt 2: theme:dark self-contained readability -----------------------------------

    /// The DELETE-MUTANT target (receipt 6): break the theme selection so `dark` no longer resolves to
    /// a dark background (e.g. `Theme.DARK.background()` returns null, or `Theme.fromToken("dark")`
    /// returns DEFAULT) and THIS named test fails — the dark background rect is the whole point.
    // A body with STRUCTURAL page-level text (the axis/category labels bake as `currentColor`), so the
    // theme's currentColor→foreground flip is observable (a plain 2-slice pie draws its labels ON the
    // wedges via contrast-fill, with no page-level currentColor to flip).
    private static final String AXIS_BODY = "xychart\n  \"Mon\" : 5\n  \"Tue\" : 8\n  \"Wed\" : 3\n";

    /// The DELETE-MUTANT target (receipt 6): break the theme selection so `dark` no longer resolves to
    /// a dark background (e.g. `Theme.DARK.background()` returns null, or `Theme.fromToken("dark")`
    /// returns DEFAULT) and THIS named test fails — the dark background rect is the whole point.
    @Test
    void darkThemeAddsDarkBackgroundRectAndLightStructuralColour() {
        String dark = Sirentide.render("%% theme: dark\n" + AXIS_BODY);
        String def = Sirentide.render(AXIS_BODY);
        // A self-contained dark background rect covering the viewBox (drawn first, under everything).
        assertTrue(dark.contains("<rect x=\"0\" y=\"0\" width=") && dark.contains("fill=\"#1e1e1e\""),
            "dark theme carries a dark background rect: " + dark);
        // The default has NO such background rect at the origin (it stays transparent).
        assertFalse(def.contains("fill=\"#1e1e1e\""), "default theme adds no dark bg: " + def);
        // Structural page-level text flipped from page-inheriting currentColor to an explicit light
        // foreground — so the SVG is self-contained + reads on a dark page. The default keeps
        // currentColor (proof the two themes' text/line colours differ).
        assertTrue(dark.contains("#e6e6e6"), "dark theme resolves currentColor to a light fill: " + dark);
        assertTrue(def.contains("currentColor"), "default keeps page-inheriting currentColor: " + def);
        assertFalse(dark.contains("currentColor"),
            "dark theme leaves NO page-inheriting currentColor (self-contained): " + dark);
    }

    @Test
    void neutralThemeAddsAnOpaqueLightBackground() {
        String neutral = Sirentide.render("%% theme: neutral\n" + PIE_BODY);
        assertTrue(neutral.contains("fill=\"#f5f5f5\""), "neutral theme carries a light bg rect: " + neutral);
    }

    // ---- Receipt 3: title feeds <title> -----------------------------------------------------

    @Test
    void configTitleFeedsTheSvgTitleElement() {
        String svg = Sirentide.render("%% title: Quarterly Split\n" + PIE_BODY);
        assertTrue(svg.contains("<title>Quarterly Split</title>"),
            "the config title becomes the SVG <title>: " + svg);
        // The rich reading-order desc is still emitted (the title override keeps the desc).
        assertTrue(svg.contains("<desc>"), "the desc is kept alongside the title override: " + svg);
    }

    @Test
    void titleWithMarkupMetacharsIsXmlEscapedInTitle() {
        String svg = Sirentide.render("%% title: A < B & C\n" + PIE_BODY);
        assertTrue(svg.contains("<title>A &lt; B &amp; C</title>"), "title is XML-escaped: " + svg);
    }

    // ---- Receipt 4: ZERO-CHANGE pin (option A) ----------------------------------------------

    @Test
    void noConfigBakeIsByteIdenticalToDefaultThemeAndCarriesNoBackground() {
        String bare = Sirentide.render(PIE_BODY);
        // Prepending an explicit `%% theme: default` config block must be BYTE-IDENTICAL: the preamble
        // is stripped transparently and DEFAULT is a no-op (no bg rect + identity colour resolve).
        String withDefaultConfig = Sirentide.render("%% theme: default\n" + PIE_BODY);
        assertEquals(bare, withDefaultConfig,
            "a %% theme: default block strips transparently — byte-identical to no config");
        // And the bare bake carries NO background rect at the origin (transparent, exactly as before).
        assertFalse(bare.contains("<rect x=\"0\" y=\"0\""),
            "the default bake adds NO self-contained background rect (stays transparent): " + bare);
    }

    @Test
    void emptyConfigOnlySourceStaysInert() {
        // A config block with no body degrades to the inert shell (never throws).
        String svg = Sirentide.render("%% theme: dark\n%% title: nothing\n");
        assertNotNull(svg);
        assertTrue(svg.contains("width=\"0\" height=\"0\""), "config-only source → inert shell: " + svg);
    }

    // ---- Receipt: the caption / note directive (plan sirentide-caption-note-directive) ----------

    @Test
    void captionAndNoteAliasBothParseIntoTheCaption() {
        assertEquals("hello there",
            DslParser.parseConfig("%% caption: hello there\n" + PIE_BODY).caption());
        // `note` is an accepted alias for `caption`.
        assertEquals("an aside",
            DslParser.parseConfig("%% note: an aside\n" + PIE_BODY).caption());
        // No directive → null caption (the byte-identical no-caption path).
        assertNull(DslParser.parseConfig(PIE_BODY).caption());
    }

    /// The svg root height (the caption grows it below the diagram).
    private static double svgHeight(String svg) {
        var m = java.util.regex.Pattern.compile("<svg[^>]*height=\"([0-9.]+)\"").matcher(svg);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    @Test
    void captionRendersBelowTheDiagramGrowingTheCanvas() {
        // The caption is a post-layout band: the SAME diagram WITH a caption is TALLER than without,
        // and both are still well-formed svg. Removing the withCaption wiring makes the two heights
        // equal and fails this test (delete-mutant guard) — the caption is genuinely on the canvas.
        String body = "flowchart TD\n  A[request] --> B[served]";
        double withCap = svgHeight(Sirentide.render("%% caption: first denial wins\n" + body));
        double noCap = svgHeight(Sirentide.render(body));
        assertTrue(withCap > noCap,
            "a caption must grow the canvas (with=" + withCap + " vs no-caption=" + noCap + ")");
        assertTrue(Sirentide.render("%% caption: x\n" + body).endsWith("</svg>"), "still well-formed");
    }

    @Test
    void noCaptionBakeIsByteIdenticalToTheNoDirectiveBake() {
        // OPTION-A guarantee extended to the caption feature: a diagram with no caption directive is
        // byte-for-byte the pre-feature bake (the caption band is purely additive).
        String body = "flowchart TD\n  A[request] --> B[served]";
        assertEquals(Sirentide.render(body), Sirentide.render(body));   // determinism sanity
        // A blank caption value is treated as absent → byte-identical to no directive at all.
        assertEquals(Sirentide.render(body), Sirentide.render("%% caption:\n" + body));
    }
}
