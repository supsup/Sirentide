package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// The flowchart GEOMETRY pins (Confluence, review sirentide/22, T1-T3 —
/// Fixpoint-accepted at /23). Each test kills a mutation that the prior suite
/// survived, PROVEN by executing the mutant:
///
/// - T1: production layering was entirely unpinned — the layer tests verified
///   a test-local re-implementation; Arrays.fill(layer, 0) passed 106/106.
///   Pinned here by parsing rect y's: chains stack at the layer pitch, and the
///   diamond's sink sits TWO rows down (longest path, not shortest).
/// - T2: back-edge lane routing was delete-green — a straight line THROUGH the
///   forward chain passed everything (the BrewShot audit only sees off-canvas
///   escapes). Pinned by line count + the lane's x clearing every node box.
/// - T3: edge-label outside-placement was revert-green (midpoint-on-the-line
///   passed). Pinned by glyph-bbox-vs-line-x comparison per side.
///
/// All assertions parse emitted geometry — no layout re-implementation, so the
/// tests cannot drift from production the way the old private layers() copy did.
class FlowchartGeometryTest {

    // Mirrors FlowchartLayout: MARGIN=24, NODE_H=36, LAYER_GAP=48.
    private static final double LAYER_PITCH = 36 + 48;

    private record Rect(double x, double y, double w, double h) { }
    private record Line(double x1, double y1, double x2, double y2) { }

    @Test
    void chainStacksDownwardAtTheLayerPitch() {
        List<Rect> rects = rects(Sirentide.render("flowchart\nA[a] --> B[b]\nB --> C[c]\n"));
        assertEquals(3, rects.size(), "three node boxes");
        // rects emit in first-seen node order: A, B, C
        double yA = rects.get(0).y, yB = rects.get(1).y, yC = rects.get(2).y;
        assertTrue(yA < yB && yB < yC, "chain must stack downward: " + yA + "," + yB + "," + yC);
        assertEquals(LAYER_PITCH, yB - yA, 0.5, "A->B gap = NODE_H+LAYER_GAP");
        assertEquals(LAYER_PITCH, yC - yB, 0.5, "B->C gap = NODE_H+LAYER_GAP");
    }

    @Test
    void diamondSinkSitsTwoRowsDownViaLongestPath() {
        List<Rect> rects = rects(Sirentide.render(
            "flowchart\nA[a] --> B[b]\nA --> C[c]\nB --> D[d]\nC --> D\n"));
        assertEquals(4, rects.size());
        double yA = rects.get(0).y, yB = rects.get(1).y, yC = rects.get(2).y, yD = rects.get(3).y;
        assertEquals(yB, yC, 0.5, "parallel branch nodes share a layer");
        assertEquals(2 * LAYER_PITCH, yD - yA, 0.5,
            "sink must sit TWO rows down (longest path) — one row = shortest-path regression");
    }

    @Test
    void backEdgeLaneRoutesAroundTheNodesNotThroughThem() {
        String svg = Sirentide.render("flowchart\nA[a] --> B[b]\nB --> C[c]\nC --> A\n");
        List<Line> lines = lines(svg);
        // 2 forward edges + the 3-segment back-edge detour; a straight-line
        // regression emits 3 (the mutation the old suite survived).
        assertEquals(5, lines.size(), "2 forward + 3 lane segments");
        List<Rect> rects = rects(svg);
        double maxRight = rects.stream().mapToDouble(r -> r.x + r.w).max().orElseThrow();
        Line lane = lines.stream()
            .filter(l -> Math.abs(l.x1 - l.x2) < 0.01 && Math.abs(l.y2 - l.y1) > LAYER_PITCH)
            .findFirst().orElseThrow(() ->
                new AssertionError("no vertical lane segment spanning layers — lane deleted?"));
        assertTrue(lane.x1 > maxRight,
            "the lane must clear every node box to the right (x=" + lane.x1
                + " vs max node right edge " + maxRight + ") — through-the-boxes is the bug");
    }

    @Test
    void edgeLabelSitsBesideTheEdgeNotOnIt() {
        // A at top, single labeled forward edge straight down to B: the label
        // glyphs must sit fully to one side of the line's x, offset by the gap.
        String svg = Sirentide.render("flowchart\nA[a] -->|yes| B[b]\n");
        List<Line> lines = lines(svg);
        assertEquals(1, lines.size(), "one forward edge line");
        double edgeX = (lines.get(0).x1 + lines.get(0).x2) / 2;
        double[] bbox = labelGlyphBboxNear(svg, lines.get(0));
        assertTrue(bbox[0] > edgeX || bbox[2] < edgeX,
            "label bbox [" + bbox[0] + ".." + bbox[2] + "] must be strictly beside the edge at x="
                + edgeX + " — straddling means the on-the-line regression is back");
    }

    // ---- geometry parsing helpers (attribute-level, no layout knowledge) ----

    private static List<Rect> rects(String svg) {
        List<Rect> out = new ArrayList<>();
        Matcher m = Pattern.compile(
            "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"")
            .matcher(svg);
        while (m.find()) {
            out.add(new Rect(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    private static List<Line> lines(String svg) {
        List<Line> out = new ArrayList<>();
        Matcher m = Pattern.compile(
            "<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" y2=\"([-0-9.]+)\"")
            .matcher(svg);
        while (m.find()) {
            out.add(new Line(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    /// Bounding box [minX, minY, maxX, maxY] of the glyph paths nearest the
    /// edge's midpoint band — the edge label. Glyph path data alternates x,y
    /// coordinates (M/L one pair, Q two pairs), so even-indexed numbers are x.
    private static double[] labelGlyphBboxNear(String svg, Line edge) {
        double midY = (edge.y1 + edge.y2) / 2;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        Matcher p = Pattern.compile("<path d=\"([^\"]+)\"").matcher(svg);
        boolean found = false;
        while (p.find()) {
            String d = p.group(1);
            List<Double> nums = new ArrayList<>();
            Matcher n = Pattern.compile("[-0-9.]+").matcher(d);
            while (n.find()) { nums.add(Double.parseDouble(n.group())); }
            // glyph outlines carry dozens of coordinates; the arrowhead
            // triangle has ~3-4 points — exclude it (it hugs the edge line
            // and would poison the label bbox with a false straddle).
            if (nums.size() < 12) { continue; }
            // is this glyph vertically near the edge midpoint? (labels sit at mid)
            double gMinY = Double.MAX_VALUE, gMaxY = -Double.MAX_VALUE;
            for (int i = 1; i < nums.size(); i += 2) {
                gMinY = Math.min(gMinY, nums.get(i));
                gMaxY = Math.max(gMaxY, nums.get(i));
            }
            if (gMaxY < midY - 20 || gMinY > midY + 20) { continue; } // not the label
            found = true;
            for (int i = 0; i < nums.size(); i += 2) {
                minX = Math.min(minX, nums.get(i));
                maxX = Math.max(maxX, nums.get(i));
            }
            minY = Math.min(minY, gMinY);
            maxY = Math.max(maxY, gMaxY);
        }
        assertTrue(found, "no label glyphs found near the edge midpoint");
        return new double[] {minX, minY, maxX, maxY};
    }
}
