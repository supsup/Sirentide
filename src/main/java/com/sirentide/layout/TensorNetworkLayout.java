package com.sirentide.layout;

import com.sirentide.font.FontMetrics;
import com.sirentide.ir.TensorNetwork;
import java.util.ArrayList;
import java.util.List;

/// Pure tensor-network (Penrose graphical notation) layout: a FIXED horizontal MPS/MPO chain.
/// Deterministic arithmetic, zero graph optimization (docs/DESIGN.md §6). N cores sit on a single
/// horizontal midline, evenly spaced by {@link #SPACING} (centre-to-centre); a BOND edge (the
/// virtual/contracted index) draws as a horizontal {@link Line} between each adjacent pair of core
/// centres, and each core carries a DANGLING PHYSICAL leg — a short vertical {@link Line} going DOWN
/// from the core. An MPO ({@link TensorNetwork#operator()}) adds a SECOND vertical leg going UP
/// (the operator's row index) per core.
///
/// Emit order is bonds → legs → cores+labels, so the core discs sit OVER the bond line they connect
/// and the leg roots tuck under the disc. Each core is a filled {@link Wedge} disc (the conventional
/// tensor-core glyph, matching the disc convention gitGraph/journey/quadrant already use) with its
/// label baked as a centred contrast-filled {@link GlyphRun} INSIDE the disc, ellipsized to the disc
/// diameter so a long label can't overrun the node. The canvas grows to hold the whole chain plus a
/// uniform {@link #MARGIN}; nothing is placed outside it (GeometryEscapeTest discipline). No new
/// element/attribute shape reaches the emitter — only rect-free line/wedge/path geometry, exactly the
/// sanitizer-survivable alphabet every other type emits.
public final class TensorNetworkLayout {

    private TensorNetworkLayout() {}

    private static final FontMetrics FONT = FontMetrics.bundled();

    /// Uniform page margin around the whole chain.
    private static final double MARGIN = 24;
    /// Core disc radius.
    private static final double CORE_R = 15;
    /// Centre-to-centre horizontal spacing between adjacent cores (> 2·CORE_R so discs never touch,
    /// leaving a clear bond segment between them).
    private static final double SPACING = 76;
    /// Length of a dangling physical/operator leg (the segment past the disc edge).
    private static final double LEG = 30;
    private static final double LABEL_SIZE = 12;
    /// Padding subtracted from the disc diameter when fitting the in-disc label.
    private static final double LABEL_PAD = 6;

    /// The tensor-core fill (a single consistent colour — all cores of an MPS/MPO are the same object
    /// class). Contract-clean `#rrggbb`; its label takes {@link Colors#contrastFill} so it reads on
    /// the disc.
    private static final String CORE_FILL = "#4e79a7";
    /// Bond + leg stroke (a neutral slate — tensor bonds/legs are drawn as plain lines).
    private static final String EDGE_STROKE = "#334155";
    private static final double BOND_WIDTH = 1.5;
    private static final double LEG_WIDTH = 1.5;

    /// Lays out the chain. A single-core `mps A` yields one disc, ZERO bond edges, and one leg; an
    /// empty chain never reaches here (the parser degrades it to {@link com.sirentide.ir.Empty}).
    public static LaidOut layout(TensorNetwork tn) {
        List<String> cores = tn.cores();
        int n = cores.size();
        boolean op = tn.operator();

        // The core midline: leave room ABOVE for the operator up-leg (MPO only) and BELOW for the
        // physical down-leg. First core centre sits one radius in from the left margin.
        double coreY = MARGIN + CORE_R + (op ? LEG : 0);
        double firstCx = MARGIN + CORE_R;

        double canvasW = MARGIN + CORE_R + (n - 1) * SPACING + CORE_R + MARGIN;
        double canvasH = coreY + CORE_R + LEG + MARGIN;

        List<Shape> shapes = new ArrayList<>();

        // 1. Bond edges (virtual indices): a horizontal segment between each adjacent pair of core
        // centres. N cores → N-1 bonds; a 1-core chain draws none. Under the discs.
        for (int i = 0; i < n - 1; i++) {
            double x1 = firstCx + i * SPACING;
            double x2 = firstCx + (i + 1) * SPACING;
            shapes.add(new Line(x1, coreY, x2, coreY, EDGE_STROKE, BOND_WIDTH));
        }

        // 2. Dangling legs (physical indices): one vertical DOWN leg per core; an MPO adds a second UP
        // leg (the operator's second physical index). Rooted at the disc edge, extending LEG past it.
        for (int i = 0; i < n; i++) {
            double cx = firstCx + i * SPACING;
            shapes.add(new Line(cx, coreY + CORE_R, cx, coreY + CORE_R + LEG, EDGE_STROKE, LEG_WIDTH));
            if (op) {
                shapes.add(new Line(cx, coreY - CORE_R, cx, coreY - CORE_R - LEG, EDGE_STROKE, LEG_WIDTH));
            }
        }

        // 3. Core discs + centred labels, over the bond line. The label is ellipsized to the disc
        // diameter so it stays inside the node (containment) and takes a contrast fill against CORE_FILL.
        for (int i = 0; i < n; i++) {
            double cx = firstCx + i * SPACING;
            shapes.add(new Wedge(cx, coreY, CORE_R, 0, 2 * Math.PI, CORE_FILL));
            String label = FONT.ellipsize(cores.get(i), 2 * CORE_R - LABEL_PAD, LABEL_SIZE);
            if (!label.isBlank()) {
                double w = FONT.runWidth(label, LABEL_SIZE);
                double baseline = coreY + LABEL_SIZE * 0.35;
                String d = FONT.textPathD(label, cx - w / 2, baseline, LABEL_SIZE);
                if (!d.isBlank()) {
                    shapes.add(new GlyphRun(d, Colors.contrastFill(CORE_FILL)));
                }
            }
        }

        return new LaidOut(canvasW, canvasH, shapes);
    }
}
