package com.sirentide.ir;

import java.util.List;

/// A 2×2 positioning matrix — the classic product-prioritization figure. A square plot is split
/// into four quadrants by a horizontal + vertical axis crossing at the centre; each axis end
/// carries a label (`Low..High`), each quadrant a label, and labelled {@link Point}s plot at
/// `(x,y)` in the unit square `[0,1]×[0,1]`.
///
/// QUADRANT NUMBERING follows Mermaid: `quadrantLabels[0]` = Q1 = TOP-RIGHT, `[1]` = Q2 =
/// TOP-LEFT, `[2]` = Q3 = BOTTOM-LEFT, `[3]` = Q4 = BOTTOM-RIGHT. Every part is OPTIONAL — a null
/// axis-end or quadrant label is simply not drawn, and a bare `quadrant` with no points still
/// renders a valid empty 2×2 grid.
///
/// `xLo`/`xHi` label the horizontal axis ends (left/right), `yLo`/`yHi` the vertical axis ends
/// (bottom/top). All four are NULLABLE (an absent end is omitted). `textColor` fills the
/// off-plot axis-end labels (they sit on the PAGE background) and defaults to `currentColor` so it
/// inherits the host page's text colour (legible on light AND dark); the DSL `color=` modifier
/// overrides it. Quadrant labels and point labels sit ON the light quadrant tints, so they take a
/// contrast-derived fill instead (docs/DESIGN.md §4/§6 — text ON a filled shape reads against that
/// fill).
public record QuadrantChart(String xLo, String xHi, String yLo, String yHi,
                            String[] quadrantLabels, List<Point> points, String textColor)
    implements Diagram {

    public QuadrantChart {
        // Normalize the quadrant-label slot to a defensive length-4 copy (nullable entries). A null
        // or wrong-length array degrades to four empty slots rather than throwing (DESIGN §6).
        String[] labels = new String[4];
        if (quadrantLabels != null) {
            for (int i = 0; i < 4 && i < quadrantLabels.length; i++) {
                labels[i] = quadrantLabels[i];
            }
        }
        quadrantLabels = labels;
        points = List.copyOf(points);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Defensive-copy accessor so a caller can't mutate the internal quadrant-label slot.
    @Override
    public String[] quadrantLabels() {
        return quadrantLabels.clone();
    }
}
