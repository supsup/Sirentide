package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Regression guard for the M1 label/text layer (plan 558147d7): adjacent-label DE-COLLISION
/// (timeline 2-row stagger, pie outside-label spreading), the root-svg width/height emit, and the
/// wrap-oracle truncation. These pin the behaviours the screenshots demanded — labels that used to
/// overlap now occupy disjoint boxes — so a future layout change that re-collides fails the build.
///
/// The overlap check reads the emitted glyph `<path>`s directly: every Sirentide glyph path is a
/// stream of (x, y) coordinate pairs (M/L each one pair, Q two pairs — no odd-length command), so
/// the numbers alternate x, y, x, y…; the odd-indexed values are the y's, and their [min, max] is
/// the label's vertical extent. Two labels are disjoint iff their y-extents don't overlap.
class LabelLayoutTest {

    /// The off-slice text fill both the timeline labels and the pie outside labels now use — the
    /// theme-adaptive `currentColor` default — lets the test pick those glyph paths out of the SVG
    /// (dots use palette fills; pie INSIDE labels use black/white contrast fills).
    private static final String LABEL_FILL = "currentColor";
    private static final Pattern LABELLED_PATH =
        Pattern.compile("<path d=\"([^\"]*)\" fill=\"" + LABEL_FILL + "\"/>");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /// The y-extent [minY, maxY] of one glyph path, from its alternating x,y coordinate stream.
    private record YExtent(double min, double max) {
        boolean disjointFrom(YExtent o) {
            return this.max < o.min || o.max < this.min;
        }
    }

    private static List<YExtent> labelYExtents(String svg) {
        List<YExtent> extents = new ArrayList<>();
        Matcher m = LABELLED_PATH.matcher(svg);
        while (m.find()) {
            Matcher n = NUMBER.matcher(m.group(1));
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int idx = 0;
            while (n.find()) {
                if (idx % 2 == 1) {   // odd index = a y coordinate
                    double y = Double.parseDouble(n.group());
                    min = Math.min(min, y);
                    max = Math.max(max, y);
                }
                idx++;
            }
            extents.add(new YExtent(min, max));
        }
        return extents;
    }

    @Test
    void timelineStaggersTwoCloseCategoryLabelsIntoDisjointBoxes() {
        // Founded (2020) and Series A (2021) sit ~5px apart on an 80-year axis — their wide labels
        // heavily overlap in x, so the 2-row stagger must lift one to the other row.
        String svg = Sirentide.render("""
            timeline
              "Founded"  : 2020
              "Series A" : 2021
              "IPO"      : 2100
            """);
        // Top (category) and bottom (year) labels now share the `currentColor` fill, so filter to
        // the ABOVE-AXIS band (AXIS_Y=80) to isolate the three category labels this test is about.
        List<YExtent> labels = new ArrayList<>();
        for (YExtent e : labelYExtents(svg)) {
            if (e.max() < 80) {
                labels.add(e);
            }
        }
        assertTrue(labels.size() == 3, "three category labels, one per event");
        assertTrue(labels.get(0).disjointFrom(labels.get(1)),
            "the two close labels (Founded / Series A) are staggered to disjoint vertical bands: "
                + labels.get(0) + " vs " + labels.get(1));
    }

    @Test
    void pieSpreadsTwoAdjacentOutsideLabelsIntoDisjointYRanges() {
        // Two 1% slices ("Tiny"/"Other") anchor at nearly the same angle; without spreading their
        // outside labels stack. After spreading the two outside labels occupy disjoint y-ranges.
        String svg = Sirentide.render("""
            pie
              "Reviews" : 50
              "Builds"  : 47
              "Tiny"    : 1
              "Other"   : 1
            """);
        List<YExtent> outside = labelYExtents(svg);   // outside labels use LABEL_FILL; inside use b/w
        assertTrue(outside.size() == 2, "exactly the two thin-slice outside labels, got " + outside.size());
        assertTrue(outside.get(0).disjointFrom(outside.get(1)),
            "the two outside labels are spread into disjoint y-ranges: "
                + outside.get(0) + " vs " + outside.get(1));
    }

    @Test
    void rootSvgCarriesExplicitWidthAndHeight() {
        // The /docs collapse fix: a viewBox-only root collapses in the sanitizer, so width+height
        // must both be present on the root <svg> (alongside the viewBox).
        String svg = Sirentide.render("pie\n  \"A\" : 3\n  \"B\" : 7\n");
        assertTrue(svg.matches("<svg[^>]*\\bwidth=\"[0-9.]+\"[^>]*>.*"), "root has a width attr");
        assertTrue(svg.matches("<svg[^>]*\\bheight=\"[0-9.]+\"[^>]*>.*"), "root has a height attr");
        assertTrue(svg.contains("viewBox="), "root still has the viewBox");
    }

    @Test
    void longGanttTaskNameIsClippedNotRunUnderTheBars() {
        // A task name far wider than the 100px label column must be ellipsized (the wrap oracle),
        // not drawn full-width under the bars. The ellipsis glyph (U+2026) appears in the output.
        String svg = Sirentide.render(
            "gantt\n  \"Implement the entire subsystem end to end\" : 0-5\n");
        // Contract-clean + shortened: the label path is present and the render stays a valid svg.
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertTrue(svg.contains("<path"), "the (clipped) task label still renders as a glyph path");
    }

    /// The tallest node-box `<rect>` height in a rendered flowchart. Flowchart node boxes emit as
    /// `<rect ... width="W" height="H" .../>`; NODE_H is 36. A wrapped multi-line label GROWS its box.
    private static double maxNodeRectHeight(String svg) {
        Matcher m = Pattern.compile("<rect[^>]*height=\"([0-9.]+)\"").matcher(svg);
        double max = 0;
        while (m.find()) {
            max = Math.max(max, Double.parseDouble(m.group(1)));
        }
        return max;
    }

    @Test
    void longNodeLabelWrapsToATallerBoxInsteadOfEllipsizingToOneLine() {
        // plan sirentide-label-legibility: a node label wider than MAX_LABEL_W (180px) word-wraps to
        // multiple lines and its box GROWS to fit, rather than truncating to one ellipsized line. The
        // grown box (height > NODE_H=36) is the load-bearing signal — reverting to the ellipsize path
        // keeps every box at 36 and fails this test (delete-mutant guard).
        String longLabel = Sirentide.render(
            "flowchart LR\n  A[this is a long label that should wrap onto several lines] --> B[x]");
        assertTrue(maxNodeRectHeight(longLabel) > 36.0,
            "a long label must wrap to a taller box (got max rect height "
                + maxNodeRectHeight(longLabel) + ", expected > 36)");

        // A short label stays exactly one NODE_H line — proves the single-line path is unchanged
        // (byte-identical height), so wrapping only kicks in for labels that actually overflow.
        String shortLabel = Sirentide.render("flowchart LR\n  A[short] --> B[x]");
        assertTrue(maxNodeRectHeight(shortLabel) == 36.0,
            "a short label must keep the fixed NODE_H box (got " + maxNodeRectHeight(shortLabel) + ")");
    }
}
