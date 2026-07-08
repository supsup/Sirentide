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

    // -- subgraph cluster frames (this milestone) -----------------------------

    // The cluster title-band fill + padding — mirrors FlowchartLayout's constants.
    private static final String BAND_FILL = "#eef2ff";
    private static final double CLUSTER_PAD = 10;      // depth-0 padding
    private static final double CLUSTER_BAND_H = 14;

    @Test
    void clusterFrameEnclosesItsMembers() {
        // A single subgraph around C and D. The frame (its band + border lines) must enclose both
        // member node rects with the pad on every side. THIS is the delete-mutant killer: stop
        // emitting the frame (or stop tracking membership) and this test goes red.
        String svg = Sirentide.render("flowchart TD\n  A[Start] --> B[Work]\n"
            + "  subgraph pipeline [Build Pipeline]\n    B --> C[Compile]\n    C --> D[Test]\n  end\n"
            + "  D --> E[Ship]\n");
        Rect band = bandRects(svg).stream().findFirst()
            .orElseThrow(() -> new AssertionError("no cluster title band emitted — frame deleted?"));
        double left = band.x, right = band.x + band.w, top = band.y;
        double bottom = frameBottom(svg, left);
        // C and D are the members (first-seen inside). Node rects are the height-36 rects.
        List<Rect> nodes = nodeRects(svg);
        // member rects = the two whose y sits below the band top (inside the frame vertically).
        List<Rect> members = new ArrayList<>();
        for (Rect r : nodes) {
            if (r.y > top && r.y + r.h < bottom + 0.5) { members.add(r); }
        }
        assertEquals(2, members.size(), "the frame contains exactly its two members (C, D)");
        for (Rect m : members) {
            assertTrue(m.x >= left && m.x + m.w <= right, "member x-range inside the frame");
            assertTrue(m.y >= top + CLUSTER_BAND_H && m.y + m.h <= bottom, "member y-range inside the frame");
        }
        double minX = members.stream().mapToDouble(r -> r.x).min().orElseThrow();
        double maxX = members.stream().mapToDouble(r -> r.x + r.w).max().orElseThrow();
        assertEquals(CLUSTER_PAD, minX - left, 0.5, "left pad = CLUSTER_PAD");
        assertEquals(CLUSTER_PAD, right - maxX, 0.5, "right pad = CLUSTER_PAD");
    }

    @Test
    void nestedClusterInsetsInsideItsParent() {
        String svg = Sirentide.render("flowchart TD\n  subgraph outer [Outer]\n    A[a] --> B[b]\n"
            + "    subgraph inner [Inner]\n      B --> C[c]\n      C --> F[f]\n    end\n    F --> G[g]\n  end\n");
        List<Rect> bands = bandRects(svg);
        assertEquals(2, bands.size(), "two title bands (outer + inner)");
        // The wider band is the outer frame (its box encloses everything); the narrower is inner.
        bands.sort((p, q) -> Double.compare(q.w, p.w));
        Rect outer = bands.get(0), inner = bands.get(1);
        double outerBottom = frameBottom(svg, outer.x), innerBottom = frameBottom(svg, inner.x);
        assertTrue(inner.x > outer.x, "inner left insets inside outer");
        assertTrue(inner.x + inner.w < outer.x + outer.w, "inner right insets inside outer");
        assertTrue(inner.y > outer.y, "inner top insets below outer top");
        assertTrue(innerBottom < outerBottom, "inner bottom insets above outer bottom");
    }

    @Test
    void canvasGrowsToFitTheFrame() {
        // The framed nodes are the widest layer, so the frame padding pushes past the plain node
        // extent → the canvas must GROW to keep the frame inside (containment). Compare against the
        // byte-twin without the subgraph wrapper, and assert the frame sits fully inside the canvas.
        String withSub = "flowchart\n  S[start] --> W[wide compile stage]\n"
            + "  subgraph g [Group]\n    W --> X[wide compile stage]\n  end\n";
        String noSub = "flowchart\n  S[start] --> W[wide compile stage]\n  W --> X[wide compile stage]\n";
        double[] a = svgSize(Sirentide.render(withSub));
        double[] b = svgSize(Sirentide.render(noSub));
        assertTrue(a[0] > b[0], "the subgraph frame grew the canvas width: " + a[0] + " vs " + b[0]);
        // Containment: the frame's box sits fully inside the declared canvas.
        String svg = Sirentide.render(withSub);
        Rect band = bandRects(svg).get(0);
        double bottom = frameBottom(svg, band.x);
        assertTrue(band.x >= 0 && band.y >= 0, "frame top-left inside canvas");
        assertTrue(band.x + band.w <= a[0] + 0.5 && bottom <= a[1] + 0.5, "frame bottom-right inside canvas");
    }

    @Test
    void flowchartWithoutSubgraphEmitsNoClusterFrame() {
        // Zero-behaviour-change pin: with no subgraph, the cluster path is inert — no title band, and
        // the byte output is exactly the pre-cluster flowchart (also pinned in GoldenSvgTest.flowchart).
        String svg = Sirentide.render("flowchart TD\n  A[Start] --> B{Ready?}\n"
            + "  B -->|yes| C[Build] --> D[Ship]\n  B -->|no| E[Fix] --> A\n");
        assertTrue(!svg.contains(BAND_FILL), "no title-band fill when there is no subgraph");
        assertTrue(bandRects(svg).isEmpty(), "no cluster frame emitted for a subgraph-free chart");
    }

    /// The title-band rects (fill == BAND_FILL) — one per drawn cluster frame; its x/y/width give the
    /// frame's left/top and width (the band spans the frame's full width).
    private static List<Rect> bandRects(String svg) {
        List<Rect> out = new ArrayList<>();
        Matcher m = Pattern.compile(
            "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\" fill=\""
                + BAND_FILL + "\"").matcher(svg);
        while (m.find()) {
            out.add(new Rect(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    /// Node boxes only — the height-NODE_H (36) rects (excludes the height-14 cluster title bands).
    private static List<Rect> nodeRects(String svg) {
        List<Rect> out = new ArrayList<>();
        for (Rect r : rects(svg)) {
            if (Math.abs(r.h - 36) < 0.5) { out.add(r); }
        }
        return out;
    }

    /// The bottom y of the cluster frame whose LEFT border sits at x≈`left`: the frame's left vertical
    /// border line runs from the band top down to the frame bottom, so its max endpoint y is the bottom.
    private static double frameBottom(String svg, double left) {
        return lines(svg).stream()
            .filter(l -> Math.abs(l.x1 - left) < 0.5 && Math.abs(l.x2 - left) < 0.5
                && Math.abs(l.y2 - l.y1) > CLUSTER_BAND_H)
            .mapToDouble(l -> Math.max(l.y1, l.y2)).max()
            .orElseThrow(() -> new AssertionError("no left border line at x=" + left));
    }

    /// Pulls the intrinsic width/height off the svg root.
    private static double[] svgSize(String svg) {
        Matcher w = Pattern.compile("<svg[^>]*\\swidth=\"([0-9.]+)\"").matcher(svg);
        Matcher h = Pattern.compile("<svg[^>]*\\sheight=\"([0-9.]+)\"").matcher(svg);
        assertTrue(w.find() && h.find(), "svg root has width+height");
        return new double[] {Double.parseDouble(w.group(1)), Double.parseDouble(h.group(1))};
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
