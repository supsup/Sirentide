package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.MathFragment;
import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.ir.ClassBox;
import com.sirentide.ir.ClassDiagram;
import com.sirentide.ir.ErAttribute;
import com.sirentide.ir.ErDiagram;
import com.sirentide.ir.ErEntity;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// White-box layout receipts for tall-math box-growth in the CLASS + ER structural layouts (plan
/// sirentide-tall-math-labels, cycle-2 follow-up). Lives in {@code com.sirentide.layout} to reach the
/// package-private geometry ({@link Rect}, {@link MathBox}, {@link Group#flatten}) directly, and builds
/// the IR by hand so a tall multi-row fragment (a matrix, spaces and all) can ride an attribute row —
/// the ER DSL attribute tokenizer splits on whitespace, so a matrix can't be authored into a DSL ER
/// row, but the LAYOUT growth is fragment-shape-agnostic and is proven here on the IR.
class TallMathClassErLayoutTest {

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();
    private static final String TALL_LATEX =
        "\\begin{matrix} a & b \\\\ c & d \\\\ e & f \\\\ g & h \\end{matrix}";
    private static final String CLASS_BOX_FILL = "#eef2ff";   // ClassDiagramLayout.BOX_FILL
    private static final String ER_BOX_FILL = "#ecfdf5";      // ErDiagramLayout.BOX_FILL

    private static double[] frag() {
        Optional<MathFragment> f = REAL.render(TALL_LATEX, 12.0);
        assertTrue(f.isPresent(), "the 4-row matrix renders");
        return new double[] {f.get().heightPx(), f.get().depthPx()};
    }

    /// The tallest background {@link Rect} of a given fill — the grown box (the matrix box is tallest).
    private static Rect tallestRect(List<Shape> shapes, String fill) {
        Rect best = null;
        for (Shape s : shapes) {
            if (s instanceof Rect r && fill.equals(r.fill()) && (best == null || r.height() > best.height())) {
                best = r;
            }
        }
        assertTrue(best != null, "a background rect of fill " + fill + " is present");
        return best;
    }

    private static MathBox firstMathBox(List<Shape> shapes) {
        for (Shape s : shapes) {
            if (s instanceof MathBox b) {
                return b;
            }
        }
        throw new AssertionError("a MathBox (the baked matrix) is present");
    }

    // -- ER: an attribute row grows to a tall matrix, the table grows, the matrix is contained --------

    @Test
    void erAttributeRowGrowsToContainATallMatrix() {
        ErAttribute matrixRow = new ErAttribute("", "$" + TALL_LATEX + "$", null);
        ErAttribute plainRow = new ErAttribute("int", "id", "PK");
        ErEntity entity = new ErEntity("M", List.of(matrixRow, plainRow));
        ErDiagram er = new ErDiagram(List.of(entity), List.of(), null);

        List<Shape> shapes = Group.flatten(ErDiagramLayout.layout(er, REAL).shapes());

        // The same table with a PLAIN first row, for the growth delta.
        ErEntity plainEntity = new ErEntity("M", List.of(new ErAttribute("", "grid", null), plainRow));
        List<Shape> plainShapes = Group.flatten(
            ErDiagramLayout.layout(new ErDiagram(List.of(plainEntity), List.of(), null), REAL).shapes());

        Rect grown = tallestRect(shapes, ER_BOX_FILL);
        Rect plain = tallestRect(plainShapes, ER_BOX_FILL);
        double[] ad = frag();
        double ext = ad[0] + ad[1];
        double rowPitch = 15.0;   // FONT.lineHeight(12)

        // (a) the table GREW by the matrix's over-one-line extent (the row absorbed ext-rowPitch).
        assertEquals(ext - rowPitch, grown.height() - plain.height(), 1.0,
            "the ER table grew by the matrix's over-one-line extent: grew="
                + (grown.height() - plain.height()) + " expected≈" + (ext - rowPitch));
        // (b) the table is at least as tall as the fragment it now holds.
        assertTrue(grown.height() >= ext - 1e-6,
            "table height >= matrix ascent+descent: h=" + grown.height() + " ext=" + ext);

        // (c) CONTAINED — the matrix ink stays inside the (grown) table box, crossing neither edge.
        MathBox mb = firstMathBox(shapes);
        double baseline = mb.y();
        double fragTop = baseline - ad[0];
        double fragBottom = baseline + ad[1];
        double boxTop = grown.y();
        double boxBottom = grown.y() + grown.height();
        assertTrue(fragTop >= boxTop - 1e-6, "matrix top inside the table: fragTop=" + fragTop
            + " boxTop=" + boxTop);
        assertTrue(fragBottom <= boxBottom + 1e-6, "matrix bottom inside the table: fragBottom="
            + fragBottom + " boxBottom=" + boxBottom);
    }

    // -- Class: a compartment row grows; the crow's-foot-analogue (relation) anchors track the box -----

    @Test
    void classAttributeRowGrowsAndTheRelationAnchorTracksTheGrownBox() {
        // Build IR directly: a class with a tall matrix attribute + a plain method, related to a plain
        // class — so the relation edge must clip to the GROWN box border, not the fixed-height one.
        ClassBox matrixClass = new ClassBox("M",
            List.of("+g $" + TALL_LATEX + "$"), List.of("+run() void"));
        ClassBox plain = new ClassBox("P", List.of(), List.of());
        ClassDiagram cd = new ClassDiagram(List.of(matrixClass, plain),
            List.of(new com.sirentide.ir.ClassRelation("M", "P",
                com.sirentide.ir.RelationKind.ASSOCIATION, null)), null);

        List<Shape> shapes = Group.flatten(ClassDiagramLayout.layout(cd, REAL).shapes());
        Rect box = tallestRect(shapes, CLASS_BOX_FILL);
        double[] ad = frag();
        double ext = ad[0] + ad[1];

        assertTrue(box.height() >= ext - 1e-6,
            "the class box grew to hold the matrix: h=" + box.height() + " ext=" + ext);

        // The relation edge (an EDGE_STROKE line) has an endpoint on the grown box's border, within its
        // taller vertical span — the anchor tracks the grown geometry (clipToRect uses the grown h).
        double left = box.x();
        double right = box.x() + box.width();
        double top = box.y();
        double bottom = box.y() + box.height();
        boolean anchored = false;
        for (Shape s : shapes) {
            if (s instanceof Line l && "#94a3b8".equals(l.stroke())) {
                if (onBorder(l.x1(), l.y1(), left, right, top, bottom)
                    || onBorder(l.x2(), l.y2(), left, right, top, bottom)) {
                    anchored = true;
                    break;
                }
            }
        }
        assertTrue(anchored, "the relation edge meets the grown class box border");
    }

    private static boolean onBorder(double x, double y, double left, double right,
                                    double top, double bottom) {
        double eps = 0.75;
        boolean inX = x >= left - eps && x <= right + eps;
        boolean inY = y >= top - eps && y <= bottom + eps;
        boolean onVert = (Math.abs(x - left) < eps || Math.abs(x - right) < eps) && inY;
        boolean onHoriz = (Math.abs(y - top) < eps || Math.abs(y - bottom) < eps) && inX;
        return onVert || onHoriz;
    }
}
