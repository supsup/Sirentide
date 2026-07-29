package com.sirentide.layout;

import com.sirentide.contract.SirentideRole;
import com.sirentide.ir.RootSystem;
import java.util.ArrayList;
import java.util.List;

/// Point/ring renderer for a finite root system's deterministic Coxeter-plane projection.
///
/// Optional bounded minimal-distance edges are drawn first, each in one EDGE anchor group.
/// Concentric distinct-radius guides follow as light, unanchored circles over the edge web, then
/// every root is a POINT anchor containing one full-circle disc. Semantic links use a one-pixel
/// stroke whose colour has at least 3:1 non-text contrast against the gallery's white canvas; dense
/// graphs remain subordinate because rings and points emit afterward. Short and long roots use
/// distinct fixed colours; a simply-laced system uses only the blue point colour. All geometry is
/// normalized into one fixed, readable square and remains within a generous margin.
public final class RootSystemLayout {

    private RootSystemLayout() {}

    private static final double CANVAS = 360;
    private static final double CENTER = CANVAS / 2;
    private static final double DISPLAY_RADIUS = 145;
    private static final String RING_STROKE = "#94a3b8";
    private static final String EDGE_STROKE = "#8490a1";   // 3.24:1 against white
    private static final double EDGE_WIDTH = 1.0;
    private static final String SHORT_ROOT = "#2563eb";
    private static final String LONG_ROOT = "#db2777";

    public static LaidOut layout(RootSystem system) {
        RootSystemProjection.Projection projection = RootSystemProjection.project(system);
        if (projection.roots().isEmpty()) {
            throw new IllegalStateException("MAX_ROOT_SYSTEM_WORK: valid type produced no roots");
        }

        double maxRadius = 0;
        long minNorm = Long.MAX_VALUE;
        long maxNorm = Long.MIN_VALUE;
        for (RootSystemProjection.ProjectedRoot root : projection.roots()) {
            maxRadius = Math.max(maxRadius, Math.hypot(root.x(), root.y()));
            minNorm = Math.min(minNorm, root.normTwice());
            maxNorm = Math.max(maxNorm, root.normTwice());
        }
        if (!(maxRadius > 0) || !Double.isFinite(maxRadius)) {
            throw new IllegalStateException("MAX_ROOT_SYSTEM_WORK: degenerate Coxeter projection");
        }
        double scale = DISPLAY_RADIUS / maxRadius;
        List<Shape> shapes = new ArrayList<>();

        AnchorAssigner assigner = new AnchorAssigner();
        // The plan is all-or-none: if the edge cap tripped, projection.edges() is empty and no
        // misleading partial minimal-distance link set reaches the canvas.
        for (RootSystemProjection.Edge edge : projection.edges()) {
            RootSystemProjection.ProjectedRoot a = projection.roots().get(edge.from());
            RootSystemProjection.ProjectedRoot b = projection.roots().get(edge.to());
            Line line = new Line(screenX(a.x(), scale), screenY(a.y(), scale),
                screenX(b.x(), scale), screenY(b.y(), scale), EDGE_STROKE, EDGE_WIDTH);
            shapes.add(new Group(assigner.assign(SirentideRole.EDGE,
                "rootedge-" + edge.from() + "-" + edge.to()), List.of(line)));
        }

        // Distinct-radius rings are structural guides, not logical roots/edges, so they stay
        // unanchored. Draw them OVER the deliberately pale dense edge web: E8's 6,720 complete links
        // must not erase its exceptional eight distinct 30-root rings. Other types can have multiple
        // Coxeter orbits sharing one radius, so the generic renderer never calls every guide an orbit.
        for (double rawRadius : projection.ringRadii()) {
            double radius = rawRadius * scale;
            shapes.add(new Path(circlePath(CENTER, CENTER, radius), "none",
                RING_STROKE, 0.75, false));
        }

        double pointRadius = projection.roots().size() <= 24 ? 3.4
            : projection.roots().size() <= 72 ? 2.9
            : projection.roots().size() <= 240 ? 2.3 : 1.55;
        boolean twoLengths = minNorm != maxNorm;
        for (int i = 0; i < projection.roots().size(); i++) {
            RootSystemProjection.ProjectedRoot root = projection.roots().get(i);
            String fill = twoLengths && root.normTwice() == maxNorm ? LONG_ROOT : SHORT_ROOT;
            Wedge point = new Wedge(screenX(root.x(), scale), screenY(root.y(), scale),
                pointRadius, 0, 2 * Math.PI, fill);
            shapes.add(new Group(assigner.assign(SirentideRole.POINT, "root-" + i), List.of(point)));
        }
        return new LaidOut(CANVAS, CANVAS, shapes);
    }

    private static double screenX(double x, double scale) {
        return CENTER + x * scale;
    }

    private static double screenY(double y, double scale) {
        return CENTER - y * scale;
    }

    private static String circlePath(double cx, double cy, double r) {
        return "M " + fmt(cx + r) + " " + fmt(cy)
            + " A " + fmt(r) + " " + fmt(r) + " 0 1 1 " + fmt(cx - r) + " " + fmt(cy)
            + " A " + fmt(r) + " " + fmt(r) + " 0 1 1 " + fmt(cx + r) + " " + fmt(cy)
            + " Z";
    }

    private static String fmt(double value) {
        double rounded = Math.round(value * 1000.0) / 1000.0;
        return rounded == Math.rint(rounded)
            ? Long.toString((long) rounded)
            : Double.toString(rounded);
    }
}
