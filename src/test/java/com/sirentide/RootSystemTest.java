package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.FramesResult;
import com.sirentide.api.Outcome;
import com.sirentide.api.Sirentide;
import com.sirentide.ir.Empty;
import com.sirentide.ir.RootSystem;
import com.sirentide.parse.DslParser;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// End-to-end parser/render/a11y/cap contract for the additive rootsystem DSL type.
class RootSystemTest {

    private static final String INERT = Sirentide.render("");

    @Test
    void parserReadsFiniteTypeAndClosedEdgeMode() {
        RootSystem r = assertInstanceOf(RootSystem.class,
            DslParser.parse("rootsystem\ntype: a3\nedges: minimal\n"));
        assertEquals('A', r.family());
        assertEquals(3, r.rank());
        assertEquals(RootSystem.Edges.MINIMAL, r.edges());

        RootSystem defaultEdges = assertInstanceOf(RootSystem.class,
            DslParser.parse("rootsystem\nG2\n"));
        assertEquals(RootSystem.Edges.MINIMAL, defaultEdges.edges());
    }

    @Test
    void parserUsesFirstValidTypeAndPermissivelyIgnoresBlockJunk() {
        RootSystem parsed = assertInstanceOf(RootSystem.class, DslParser.parse("""
            rootsystem
            prose before the type
            type: H4
            unknown-directive: ignored
            type: A2
            type: not-a-type
            G2
            edges: none
            trailing prose
            """));
        assertEquals('A', parsed.family(), "the first valid type wins");
        assertEquals(2, parsed.rank());
        assertEquals(RootSystem.Edges.NONE, parsed.edges());

        assertInstanceOf(Empty.class, DslParser.parse("""
            rootsystem
            type:
            type: H4
            junk
            """), "a block with no valid type still degrades to the inert shell");
        assertInstanceOf(Empty.class, DslParser.parse("""
            rootsystem
            type: A2
            edges: none
            edges: all
            """), "a recognized malformed edges directive rejects the whole block");
    }

    @Test
    void malformedUnknownAndOutOfRangeTypesUseTheUniversalInertShell() {
        for (String bad : new String[] {
            "", "A", "A0", "A 3", "A-3", "Z9", "H4",
            "B1", "C1", "D3", "E5", "E9", "F3", "G3", "A25", "B25", "A999999999999999999999"
        }) {
            assertEquals(INERT, Sirentide.render("rootsystem\ntype: " + bad + "\n"),
                bad + " must degrade to Sirentide's byte-identical inert shell");
        }
        assertEquals(INERT, Sirentide.render("rootsystem\ntype: A3\nedges: all\n"),
            "edges has a closed minimal|none vocabulary");
        assertInstanceOf(Empty.class, DslParser.parse("rootsystem\n"));
    }

    @Test
    void a3A11yTeachesTheLieAlgebraRootCountAndCoxeterNumber() {
        String svg = Sirentide.render("rootsystem\ntype: A3\nedges: none\n");
        assertNotEquals(INERT, svg);
        assertEquals("A3 root system", between(svg, "<title>", "</title>"));
        assertTrue(between(svg, "<desc>", "</desc>")
            .startsWith("A3 root system (sl4): 12 roots, Coxeter number 4."),
            "the accessible description teaches the finite-type correspondence: " + svg);
    }

    @Test
    void a11yNamesTheLieAlgebraCorrespondenceForEveryFamily() {
        for (String[] fixture : new String[][] {
            {"A3", "sl4", "12", "4"},
            {"B4", "so9", "32", "8"},
            {"C4", "sp8", "32", "8"},
            {"D4", "so8", "24", "6"},
            {"E8", "E8", "240", "30"},
            {"F4", "F4", "48", "12"},
            {"G2", "G2", "12", "6"}
        }) {
            String desc = between(Sirentide.render("rootsystem\ntype: " + fixture[0]
                + "\nedges: none\n"), "<desc>", "</desc>");
            assertTrue(desc.startsWith(fixture[0] + " root system (" + fixture[1] + "): "
                    + fixture[2] + " roots, Coxeter number " + fixture[3] + "."),
                fixture[0] + " a11y correspondence/count/Coxeter triple: " + desc);
        }
    }

    @Test
    void smallA2IsReadableAsSixPointsOneRingAndSixMinimalEdges() {
        String svg = Sirentide.render("rootsystem\ntype: A2\nedges: minimal\n");
        assertEquals(6, count(svg, "data-sirentide-role=\"point\""));
        assertEquals(6, count(svg, "data-sirentide-role=\"edge\""));
        assertEquals(6, count(svg, "<line "));
        // One decorative ring path has fill=none/stroke; six point discs are emitted as path wedges.
        assertEquals(1, count(svg, "fill=\"none\" stroke=\"#94a3b8\""));
        assertTrue(svg.contains("6 ambient minimal-distance root links shown."));
    }

    @Test
    void semanticMinimalLinksMeetNonTextContrastAndVisibleWidthOnWhite() {
        String svg = Sirentide.render("rootsystem\ntype: A2\nedges: minimal\n");
        Matcher links = Pattern.compile(
            "<line [^>]*stroke=\"(#[0-9a-fA-F]{6})\" stroke-width=\"([0-9.]+)\"/>")
            .matcher(svg);
        int seen = 0;
        while (links.find()) {
            seen++;
            String stroke = links.group(1);
            double width = Double.parseDouble(links.group(2));
            assertTrue(contrastRatio(stroke, "#ffffff") >= 3.0,
                stroke + " must retain WCAG non-text contrast against white");
            assertTrue(width >= 1.0, "semantic links must remain visibly at least one CSS px wide");
        }
        assertEquals(6, seen, "every A2 semantic edge uses the contrast-pinned line treatment");
        assertEquals(seen, count(svg, "data-sirentide-role=\"edge\""),
            "the visible lines remain inside semantic EDGE anchors");
    }

    @Test
    void edgesNoneKeepsPointsAndRingsButEmitsNoPolytopeLines() {
        String svg = Sirentide.render("rootsystem\ntype: G2\nedges: none\n");
        assertEquals(12, count(svg, "data-sirentide-role=\"point\""));
        assertEquals(0, count(svg, "data-sirentide-role=\"edge\""));
        assertEquals(0, count(svg, "<line "));
        assertEquals(2, count(svg, "fill=\"none\" stroke=\"#94a3b8\""),
            "G2 renders the short-root and long-root guide rings");
    }

    @Test
    void e8MinimalShowpieceRendersEveryEdgeUnderTheBoundedCap() {
        String svg = Sirentide.render("rootsystem\ntype: E8\nedges: minimal\n");
        assertNotEquals(INERT, svg);
        assertEquals(240, count(svg, "data-sirentide-role=\"point\""));
        assertEquals(6720, count(svg, "data-sirentide-role=\"edge\""));
        assertEquals(6720, count(svg, "<line "));
        String desc = between(svg, "<desc>", "</desc>");
        assertTrue(desc.contains("6720 ambient minimal-distance root links shown."));
        assertTrue(svg.length() < Sirentide.MAX_OUTPUT_BYTES,
            "the full E8 showpiece remains under the independent emitter byte cap: " + svg.length());
    }

    @Test
    void e8MinimalShowpieceIsExplicitlyStaticOnlyPastTheFrameCap() {
        String dsl = "rootsystem\ntype: E8\nedges: minimal\n";
        String staticSvg = Sirentide.render(dsl);
        assertNotEquals(INERT, staticSvg, "the static E8 receipt remains supported");
        assertEquals(List.of(INERT), Sirentide.renderFrames(dsl),
            "6,720 edges + 240 roots exceed the 512-frame cap and fail closed before frame baking");

        FramesResult diagnosed = Sirentide.renderFramesWithDiagnostics(dsl);
        assertEquals(List.of(INERT), diagnosed.frames());
        assertEquals(Outcome.OUTPUT_CAP_EXCEEDED, diagnosed.diagnostics().outcome());
        assertTrue(diagnosed.diagnostics().detail().contains("frame count 6960 > MAX_FRAMES"),
            "the static-only boundary is named exactly: " + diagnosed.diagnostics());
    }

    @Test
    void a24EdgeCapDegradeIsLoudAndNeverPartial() {
        String svg = Sirentide.render("rootsystem\ntype: A24\nedges: minimal\n");
        assertNotEquals(INERT, svg, "edge cap keeps the complete point/ring projection");
        assertEquals(600, count(svg, "data-sirentide-role=\"point\""));
        assertEquals(0, count(svg, "data-sirentide-role=\"edge\""),
            "over-cap minimal edges degrade all the way to none");
        String desc = between(svg, "<desc>", "</desc>");
        assertTrue(desc.contains("Minimal-distance edges omitted: 13800 exceeds the 10000-edge cap"),
            "the cap degrade is declarative, not silent: " + desc);
        assertTrue(desc.contains("rendered as edges none"));
    }

    @Test
    void representativeOutputIsByteDeterministic() {
        for (String type : new String[] {"A2", "A3", "G2", "E8", "F4"}) {
            String dsl = "rootsystem\ntype: " + type + "\nedges: minimal\n";
            String first = Sirentide.render(dsl);
            assertEquals(first, Sirentide.render(dsl), type + " second bake");
            assertEquals(first, Sirentide.render(dsl), type + " third bake");
        }
    }

    @Test
    void classicalRankCapBoundaryIsInclusiveAndRootBounded() {
        for (String[] fixture : new String[][] {
            {"A24", "600"},
            {"B24", "1152"},
            {"C24", "1152"},
            {"D24", "1104"}
        }) {
            RootSystem parsed = assertInstanceOf(RootSystem.class,
                DslParser.parse("rootsystem\ntype: " + fixture[0] + "\nedges: none\n"));
            assertEquals(Integer.parseInt(fixture[1]), parsed.rootCount());
            String svg = Sirentide.render("rootsystem\ntype: " + fixture[0] + "\nedges: none\n");
            assertNotEquals(INERT, svg, fixture[0] + " is the inclusive admitted rank boundary");
            assertEquals(parsed.rootCount(), count(svg, "data-sirentide-role=\"point\""));
        }
    }

    @Test
    void hostileOrHugeInputNeverEscapesThePublicBake() {
        for (String token : new String[] {
            "<script>", "A2147483648", "E8\" onload=\"x", "\u0000", "A999999999999999999999999999999"
        }) {
            String svg = Sirentide.render("rootsystem\ntype: " + token + "\nedges: minimal\n");
            assertEquals(INERT, svg);
            assertFalse(svg.contains("<script"));
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static double contrastRatio(String first, String second) {
        double a = relativeLuminance(first);
        double b = relativeLuminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double relativeLuminance(String hex) {
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double channel = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            linear[i] = channel <= 0.04045
                ? channel / 12.92
                : Math.pow((channel + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    private static String between(String s, String open, String close) {
        int start = s.indexOf(open);
        if (start < 0) {
            return "";
        }
        start += open.length();
        int end = s.indexOf(close, start);
        return end < 0 ? "" : s.substring(start, end);
    }
}
