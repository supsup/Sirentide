package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Sankey;
import com.sirentide.parse.DslParser;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// The sankey GEOMETRY pins (plan sirentide-sankey). Nodes fall into depth COLUMNS (sources left,
/// sinks right); a node's HEIGHT is its max(inflow-sum, outflow-sum) · a shared scale; each flow draws
/// as a filled quadrilateral BAND whose width is proportional to its value, connecting the source's
/// right edge to the target's left edge at cumulative outflow / inflow slot offsets.
///
/// Lives in {@code com.sirentide.layout} to reach {@link SankeyLayout#layout(Sankey)} directly — the
/// tests assert on the laid-out {@link Rect}/{@link Path} geometry, never re-implement layout.
class SankeyLayoutTest {

    /// The canonical example: Coal(25)+Gas(15) → Electricity → Homes(20)+Industry(20). Three columns;
    /// Electricity is a middle node with in-sum 40 == out-sum 40.
    private static final String DSL =
        "sankey\n  Coal,Electricity,25\n  Gas,Electricity,15\n"
            + "  Electricity,Homes,20\n  Electricity,Industry,20\n";

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /// Map each node NAME → its laid-out bar {@link Rect}, read from the `<g role="node">` groups.
    private static Map<String, Rect> nodes(LaidOut laid) {
        Map<String, Rect> out = new HashMap<>();
        for (Shape s : laid.shapes()) {
            if (s instanceof Group g && g.anchor().role() == com.sirentide.contract.SirentideRole.NODE) {
                for (Shape m : g.members()) {
                    if (m instanceof Rect r) {
                        out.put(g.anchor().id(), r);
                    }
                }
            }
        }
        return out;
    }

    /// Map each flow `source-target` id → the eight numbers of its band quad's `d` string:
    /// {@code M srcX srcY0 L tgtX tgtY0 L tgtX tgtY1 L srcX srcY1 Z}.
    private static Map<String, double[]> bands(LaidOut laid) {
        Map<String, double[]> out = new HashMap<>();
        for (Shape s : laid.shapes()) {
            if (s instanceof Group g && g.anchor().role() == com.sirentide.contract.SirentideRole.FLOW) {
                for (Shape m : g.members()) {
                    if (m instanceof Path p) {
                        String[] t = p.d().split("\\s+");
                        // indices: 1=srcX 2=srcY0 4=tgtX 5=tgtY0 7=tgtX 8=tgtY1 10=srcX 11=srcY1
                        out.put(g.anchor().id(), new double[] {
                            Double.parseDouble(t[1]), Double.parseDouble(t[2]),
                            Double.parseDouble(t[4]), Double.parseDouble(t[5]),
                            Double.parseDouble(t[8]), Double.parseDouble(t[11])});
                    }
                }
            }
        }
        return out;
    }

    private static LaidOut lay(String dsl) {
        return SankeyLayout.layout((Sankey) DslParser.parse(dsl));
    }

    @Test
    void columnsAreAssignedByDepth() {
        Map<String, Rect> ns = nodes(lay(DSL));
        // Sources share the leftmost column; the middle node one column right; the sinks rightmost.
        assertTrue(near(ns.get("Coal").x(), ns.get("Gas").x()), "Coal + Gas share column 0");
        assertTrue(near(ns.get("Homes").x(), ns.get("Industry").x()), "Homes + Industry share the last column");
        assertTrue(ns.get("Coal").x() < ns.get("Electricity").x(), "sources left of the middle node");
        assertTrue(ns.get("Electricity").x() < ns.get("Homes").x(), "middle node left of the sinks");
    }

    /// DELETE-MUTANT SENTINEL #1 (receipt #4): a node's HEIGHT is proportional to its max(inflow,
    /// outflow) sum. Coal (out 25) and Gas (out 15) share a column, so their heights must be in a 25:15
    /// ratio; Electricity (in 40 == out 40) is the tallest. Pin a node to a constant height and this fails.
    @Test
    void aNodeHeightIsProportionalToItsMaxInOutSum() {
        Map<String, Rect> ns = nodes(lay(DSL));
        double coal = ns.get("Coal").height();
        double gas = ns.get("Gas").height();
        double elec = ns.get("Electricity").height();
        assertTrue(near(coal / gas, 25.0 / 15.0), "Coal:Gas height ratio tracks 25:15, got " + (coal / gas));
        // Electricity's value is max(in 40, out 40) = 40 → its height is 40/25 of Coal's (both scaled equally).
        assertTrue(near(elec / coal, 40.0 / 25.0), "Electricity:Coal height ratio tracks 40:25, got " + (elec / coal));
    }

    /// DELETE-MUTANT SENTINEL #2 (receipt #6): a band's WIDTH is proportional to its value. The
    /// Coal→Electricity band (value 25) and the Electricity→Homes band (value 20) must be in a 25:20
    /// width ratio. Replace `value·scale` with a FIXED width in {@link SankeyLayout} and the ratio
    /// collapses to 1 ≠ 25/20 → this fails BY NAME. Named + observed (receipt #6).
    @Test
    void aBandWidthIsProportionalToItsValue() {
        Map<String, double[]> bs = bands(lay(DSL));
        double[] coalElec = bs.get("Coal-Electricity");
        double[] elecHomes = bs.get("Electricity-Homes");
        double wCoal = coalElec[5] - coalElec[1];    // srcY1 - srcY0 = the band's width on its source edge
        double wHomes = elecHomes[5] - elecHomes[1];
        assertTrue(wCoal > 0 && wHomes > 0, "bands have positive width");
        assertTrue(near(wCoal / wHomes, 25.0 / 20.0),
            "Coal→Electricity : Electricity→Homes width ratio tracks 25:20, got " + (wCoal / wHomes));
        // The band's two parallel edges carry the SAME width (a proper ribbon, not a wedge).
        double srcW = coalElec[5] - coalElec[1];
        double tgtW = coalElec[4] - coalElec[3];     // tgtY1 - tgtY0
        assertTrue(near(srcW, tgtW), "the band width is equal on both edges: " + srcW + " vs " + tgtW);
    }

    @Test
    void aBandConnectsTheSourceRightEdgeToTheTargetLeftEdge() {
        LaidOut laid = lay(DSL);
        Map<String, Rect> ns = nodes(laid);
        Map<String, double[]> bs = bands(laid);
        Rect coal = ns.get("Coal");
        Rect elec = ns.get("Electricity");
        double[] band = bs.get("Coal-Electricity");
        assertTrue(near(band[0], coal.x() + coal.width()), "band starts at the source's RIGHT edge");
        assertTrue(near(band[2], elec.x()), "band ends at the target's LEFT edge");
        // Coal is the sole outflow of Coal → its band leaves Coal's top (offset 0).
        assertTrue(near(band[1], coal.y()), "the band leaves the source's top slot (first outflow)");
        // Coal is the FIRST inflow declared into Electricity → it lands at Electricity's top slot.
        assertTrue(near(band[3], elec.y()), "the first inflow lands at the target's top slot");
    }

    /// The cumulative INFLOW offset: Gas is the SECOND flow into Electricity, so its band lands BELOW
    /// the Coal band on Electricity's left edge — at Electricity.top + the Coal band's width.
    @Test
    void inflowSlotsStackInDeclarationOrder() {
        LaidOut laid = lay(DSL);
        Map<String, Rect> ns = nodes(laid);
        Map<String, double[]> bs = bands(laid);
        double[] coalBand = bs.get("Coal-Electricity");
        double[] gasBand = bs.get("Gas-Electricity");
        double coalWidth = coalBand[4] - coalBand[3];    // width on Electricity's edge
        double elecTop = ns.get("Electricity").y();
        assertTrue(near(gasBand[3], elecTop + coalWidth),
            "the Gas inflow stacks below the Coal inflow: " + gasBand[3] + " vs " + (elecTop + coalWidth));
    }

    @Test
    void theCanvasContainsEveryNodeAndBand() {
        LaidOut laid = lay(DSL);
        double w = laid.width();
        double h = laid.height();
        for (Rect r : nodes(laid).values()) {
            assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.width() <= w && r.y() + r.height() <= h,
                "node escapes the canvas: " + r + " canvas " + w + "x" + h);
        }
        for (double[] b : bands(laid).values()) {
            // The band's source/target x are within [0,w] and its y-corners within [0,h].
            assertTrue(b[0] >= 0 && b[0] <= w && b[2] >= 0 && b[2] <= w, "band x within canvas");
            assertTrue(b[1] >= 0 && b[5] <= h && b[3] >= 0 && b[4] <= h, "band y within canvas");
        }
    }

    @Test
    void anEmptySankeyLaysOutAMinimalInertCanvasNotAThrow() {
        LaidOut laid = lay("sankey\n");
        assertTrue(laid.width() > 0 && laid.height() > 0, "a real (non-zero) canvas");
        assertEquals(0, nodes(laid).size(), "no node bars on an empty sankey");
    }
}
