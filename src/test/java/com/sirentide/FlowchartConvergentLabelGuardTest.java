package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.sirentide.api.Sirentide;
import org.junit.jupiter.api.Test;

/// GUARD for convergent-edge labels in flowchart (plan ea20153b part 2). Ported from the red-first
/// measurement branch confluence/flowchart-label-guard @ 277f3f1c and adapted to today's
/// FlowchartLayout.
///
/// HISTORY the assertions encode: two independent reviews (Fixpoint Coordination Room sirentide
/// 271/273, Lattice 276) argued convergent-edge labels are **x-separated by construction** — each
/// edge label sits at its own edge-midpoint x — so a target-keyed de-collision could only ever fire
/// on already-disjoint labels. On the strength of that argument the de-collision was WITHDRAWN.
/// The measurement branch pinned the property and it MEASURED FALSE: two convergent labels overprint
/// in BOTH axes (midpoint-x separation smaller than half the combined label BOX widths still
/// overprints — a label is a box around its midpoint, not a point). That withdrawal premise is now
/// RETRACTED (sirentide/514): the reviewers conflated anchor-x separation with rendered-box-x
/// separation. This guard pins the corrected property — convergent labels must be pairwise
/// 2D-bbox-disjoint AND inside the declared canvas — which the de-collision pass (target-keyed
/// vertical stacking on measured rendered-box overlap) now delivers. It asserts emitted geometry
/// only (no layout re-implementation), identity-free (bbox properties, not "which label is which").
class FlowchartConvergentLabelGuardTest {

    // Canonical convergent case (reviewer's example): two labeled edges into one target.
    private static final String CONVERGENT_TD =
        "flowchart TD\nA[a] -->|resume| X[x]\nB[b] -->|exit| X\n";
    // Same edges, reversed declaration order — the order-independence pin (kills B2).
    private static final String CONVERGENT_TD_REORDERED =
        "flowchart TD\nB[b] -->|exit| X[x]\nA[a] -->|resume| X\n";
    private static final String CONVERGENT_LR =
        "flowchart LR\nA[a] -->|resume| X[x]\nB[b] -->|exit| X\n";

    @Test
    void convergentLabelsArePairwiseXDisjointTD() {
        assertConvergentLabelsDisjointAndInCanvas(CONVERGENT_TD);
    }

    @Test
    void convergentLabelsStayDisjointWhenEdgesReordered() {
        // B1/B2 killer: the property must not depend on authored edge order.
        assertConvergentLabelsDisjointAndInCanvas(CONVERGENT_TD_REORDERED);
    }

    @Test
    void convergentLabelsArePairwiseXDisjointLR() {
        assertConvergentLabelsDisjointAndInCanvas(CONVERGENT_LR);
    }

    @Test
    void convergentLabelsStayInCanvasAcrossPlaybackFrames() {
        // The off-canvas (B1) property must hold on EVERY frame, not just the static render.
        List<String> frames = Sirentide.renderFrames(CONVERGENT_TD);
        assertTrue(frames.size() > 1, "a two-hop convergent chart plays through several frames");
        for (int i = 0; i < frames.size(); i++) {
            String svg = frames.get(i);
            double[] canvas = svgSize(svg);
            for (double[] b : edgeLabelBboxes(svg)) {
                assertInCanvas(b, canvas, "frame " + i);
            }
        }
    }

    /// The load-bearing assertion: every edge label sits inside the declared canvas, and no two
    /// overlap in x AND y simultaneously (2D-bbox-disjoint — the corrected property the de-collision
    /// delivers by stacking a colliding label a line below its convergent sibling).
    private static void assertConvergentLabelsDisjointAndInCanvas(String dsl) {
        String svg = Sirentide.render(dsl);
        double[] canvas = svgSize(svg);
        List<double[]> labels = edgeLabelBboxes(svg);
        assertEquals(2, labels.size(),
            "exactly two convergent edge labels expected (non-vacuous guard); found " + labels.size());
        for (double[] b : labels) {
            assertInCanvas(b, canvas, "static");
        }
        double[] p = labels.get(0), q = labels.get(1);
        // Pairwise 2D-bbox-disjoint: the boxes must not overlap in x AND y simultaneously. Before the
        // fix these two convergent labels share a rectangle (overprint); the de-collision stacks the
        // colliding one down so they become y-separated.
        boolean disjoint = p[2] <= q[0] || q[2] <= p[0]   // x-separated
            || p[3] <= q[1] || q[3] <= p[1];              // or y-separated
        assertTrue(disjoint,
            "convergent labels must be pairwise 2D-disjoint, not overprinting: "
                + bbox(p) + " vs " + bbox(q));
    }

    private static void assertInCanvas(double[] b, double[] canvas, String where) {
        assertTrue(b[0] >= -0.5 && b[1] >= -0.5,
            where + ": label escapes canvas top/left: [" + b[0] + "," + b[1] + "]");
        assertTrue(b[2] <= canvas[0] + 0.5 && b[3] <= canvas[1] + 0.5,
            where + ": label escapes canvas bottom/right: [" + b[2] + "," + b[3]
                + "] vs canvas " + canvas[0] + "x" + canvas[1]);
    }

    /// Edge-label bounding boxes [minX, minY, maxX, maxY], one per label. Edge labels are the
    /// multi-glyph `<path>` runs whose center is NOT inside any node box (that excludes node-name
    /// labels) and which carry enough coordinates to be a word, not an arrowhead. Each edge-label
    /// GlyphRun is one `<path>`, so one path == one label.
    private static List<double[]> edgeLabelBboxes(String svg) {
        List<double[]> nodeRects = rects(svg);
        List<double[]> out = new ArrayList<>();
        Matcher p = Pattern.compile("<path d=\"([^\"]+)\"").matcher(svg);
        while (p.find()) {
            List<Double> nums = new ArrayList<>();
            Matcher n = Pattern.compile("[-0-9.]+").matcher(p.group(1));
            while (n.find()) { nums.add(Double.parseDouble(n.group())); }
            if (nums.size() < 12) { continue; }   // arrowhead / tiny glyph — not a word label
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < nums.size(); i += 2) {
                minX = Math.min(minX, nums.get(i));   maxX = Math.max(maxX, nums.get(i));
                minY = Math.min(minY, nums.get(i + 1)); maxY = Math.max(maxY, nums.get(i + 1));
            }
            double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
            boolean insideANode = false;
            for (double[] r : nodeRects) {
                if (cx >= r[0] && cx <= r[0] + r[2] && cy >= r[1] && cy <= r[1] + r[3]) {
                    insideANode = true;
                    break;
                }
            }
            if (!insideANode) { out.add(new double[] {minX, minY, maxX, maxY}); }
        }
        return out;
    }

    /// Node boxes as [x, y, w, h].
    private static List<double[]> rects(String svg) {
        List<double[]> out = new ArrayList<>();
        Matcher m = Pattern.compile(
            "<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"")
            .matcher(svg);
        while (m.find()) {
            out.add(new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))});
        }
        return out;
    }

    private static String bbox(double[] b) {
        return "[x " + b[0] + ".." + b[2] + ", y " + b[1] + ".." + b[3] + "]";
    }

    private static double[] svgSize(String svg) {
        Matcher w = Pattern.compile("<svg[^>]*\\swidth=\"([0-9.]+)\"").matcher(svg);
        Matcher h = Pattern.compile("<svg[^>]*\\sheight=\"([0-9.]+)\"").matcher(svg);
        assertTrue(w.find() && h.find(), "svg root has width+height");
        return new double[] {Double.parseDouble(w.group(1)), Double.parseDouble(h.group(1))};
    }
}
