package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/// Plan 5ce905a8, review 1335 F1: no label backing plate is ever painted over an arrowhead.
///
/// Read from the EMITTED SVG only, never from layout constants: every committed flowchart and
/// sequence golden, every play-through frame of those fixtures, and both plan fixtures with their
/// frames. In paint order, each arrowhead of an edge or message group (a filled `M L L Z` triangle,
/// or the two short strokes of a reply's open V) is point-sampled, and every plate painted AFTER it
/// (a `#f5f7ff` rect, or a `#f5f7ff` path with arrowheads cut out under the non-zero rule; `#fbfcff`
/// in a future frame) must cover none of those points. At the review tip this failed on
/// flowchart-convergent (edge B-S's plate over 14 of its own arrowhead's samples) and
/// flowchart-convergent-fan (B-S's plate over its own and A-S's arrowheads).
class LabelPlateArrowheadGoldenTest {

    private static final Pattern NUM = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern TRIANGLE = Pattern.compile(
        "M (-?[0-9.]+) (-?[0-9.]+) L (-?[0-9.]+) (-?[0-9.]+) L (-?[0-9.]+) (-?[0-9.]+) Z");
    /// A reply's open-V stroke is about 10 px; every message and edge line is longer than this.
    private static final double V_STROKE_MAX = 11;

    private record Head(String where, List<double[]> samples, double[] box) {}

    private record Plate(String where, Element el, double[] box) {}

    @Test
    void noPlateIsPaintedOverAnyArrowheadInAnyGoldenOrFrame() throws Exception {
        Map<String, String> svgs = new LinkedHashMap<>();
        for (var e : GoldenSvgTest.fixtures().entrySet()) {
            if (e.getKey().startsWith("flowchart") || e.getKey().startsWith("sequence")) {
                svgs.put(e.getKey() + " golden", GoldenSvgTest.golden(e.getKey()));
                addFrames(svgs, e.getKey(), e.getValue());
            }
        }
        for (String fixture : List.of("/flowchart-diamond-lane-live.mmd",
                "/sequence/lane-live-acceptance-diagram1.sirentide")) {
            String dsl = resource(fixture);
            svgs.put(fixture + " render", Sirentide.render(dsl));
            addFrames(svgs, fixture, dsl);
        }
        Scan scan = scan(svgs);
        assertEquals("", String.join("\n", scan.covered()), "plates painted over arrowheads");
        // Non-vacuous: plates and arrowheads were read. Whether a fixture still has a plate next to
        // an earlier arrowhead depends on label placement (review 1337 F1 moved the labels that sat
        // on arrowheads), so the cut-out reading is proven on a fixed scene instead, by
        // theSamplerSeesAnUncutPlateOverAnArrowheadAndNotACutOne.
        assertTrue(scan.plates() > 0 && scan.heads() > 0, "plates " + scan.plates() + ", arrowheads " + scan.heads());
    }

    /// Healthy control for the sampler, varied only in whether the later plate has the earlier
    /// arrowhead cut out of it: an edge's arrowhead (tip 20,30, base at y 20) and a later edge's
    /// plate over the lower part of it. As a plain rect the plate covers samples of the arrowhead;
    /// as a path with the clipped triangle wound the other way it covers none, and that pass is not
    /// vacuous (the pair is near and the plate is a cut path).
    @Test
    void theSamplerSeesAnUncutPlateOverAnArrowheadAndNotACutOne() throws Exception {
        Scan uncut = scan(Map.of("uncut", headScene(
            "<rect x=\"10\" y=\"22\" width=\"40\" height=\"18\" fill=\"#f5f7ff\"/>")));
        assertEquals(1, uncut.covered().size(), "an uncut plate over an earlier arrowhead: " + uncut.covered());
        Scan cut = scan(Map.of("cut", headScene("<path d=\"M 10 22 L 50 22 L 50 40 L 10 40 Z "
            + "M 20 30 L 23.2 22 L 16.8 22 Z\" fill=\"#f5f7ff\"/>")));
        assertEquals(List.of(), cut.covered(), "the arrowhead is cut out of the plate");
        assertEquals(1, cut.nearPairs(), "the plate and the arrowhead are a near pair");
        assertEquals(1, cut.cutPlates(), "the plate is a cut path");
    }

    private static String headScene(String plate) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"60\" height=\"40\">"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"A-B\" data-sirentide-seq=\"1\">"
            + "<path d=\"M 20 30 L 16 20 L 24 20 Z\" fill=\"#94a3b8\"/></g>"
            + "<g data-sirentide-role=\"edge\" data-sirentide-id=\"C-D\" data-sirentide-seq=\"2\">"
            + plate + "</g></svg>";
    }

    private record Scan(List<String> covered, int plates, int cutPlates, int heads, int nearPairs) {}

    /// Every plate painted over a sample of an arrowhead painted before it, over `svgs`, with the
    /// counts that show what was read.
    private static Scan scan(Map<String, String> svgs) throws Exception {
        List<String> covered = new ArrayList<>();
        int plates = 0;
        int cutPlates = 0;
        int heads = 0;
        int nearPairs = 0;
        for (var e : svgs.entrySet()) {
            List<Object> order = new ArrayList<>();
            walk(parse(e.getValue()).getDocumentElement(), null, e.getKey(), order);
            List<Head> seen = new ArrayList<>();
            for (Object o : order) {
                if (o instanceof Head h) {
                    seen.add(h);
                    heads++;
                    continue;
                }
                Plate p = (Plate) o;
                plates++;
                if ("path".equals(p.el().getLocalName())) {
                    cutPlates++;
                }
                for (Head h : seen) {
                    if (!overlaps(p.box(), h.box())) {
                        continue;
                    }
                    nearPairs++;
                    int n = 0;
                    for (double[] s : h.samples()) {
                        if (covers(p, s)) {
                            n++;
                        }
                    }
                    if (n > 0) {
                        covered.add(String.format(Locale.ROOT, "%s: plate at %.1f,%.1f covers %d of %d samples of %s",
                            p.where(), p.box()[0], p.box()[1], n, h.samples().size(), h.where()));
                    }
                }
            }
        }
        return new Scan(covered, plates, cutPlates, heads, nearPairs);
    }

    private static void addFrames(Map<String, String> svgs, String name, String dsl) {
        List<String> frames = Sirentide.renderFrames(dsl);
        for (int i = 0; i < frames.size(); i++) {
            svgs.put(name + " frame " + i, frames.get(i));
        }
    }

    /// Paint-order walk collecting arrowheads (inside edge and message groups) and plates.
    private static void walk(Element el, String group, String svg, List<Object> out) {
        String g = el.hasAttribute("data-sirentide-role")
            ? el.getAttribute("data-sirentide-role") + ":" + el.getAttribute("data-sirentide-id") : group;
        boolean layer = g != null && (g.startsWith("edge:") || g.startsWith("message:"));
        String fill = el.getAttribute("fill");
        boolean plateFill = "#f5f7ff".equals(fill) || "#fbfcff".equals(fill);
        switch (el.getLocalName()) {
            case "rect" -> {
                if (plateFill) {
                    double x = num(el, "x");
                    double y = num(el, "y");
                    out.add(new Plate(svg + " " + g, el,
                        new double[] {x, y, x + num(el, "width"), y + num(el, "height")}));
                }
            }
            case "path" -> {
                String d = el.getAttribute("d");
                Matcher t = TRIANGLE.matcher(d);
                if (plateFill) {
                    out.add(new Plate(svg + " " + g, el, box(d)));
                } else if (layer && t.matches()) {
                    double[] v = new double[6];
                    for (int i = 0; i < 6; i++) {
                        v[i] = Double.parseDouble(t.group(i + 1));
                    }
                    out.add(new Head(g + " arrowhead", triangleSamples(v), box(d)));
                }
            }
            case "line" -> {
                double x1 = num(el, "x1");
                double y1 = num(el, "y1");
                double x2 = num(el, "x2");
                double y2 = num(el, "y2");
                double len = Math.hypot(x2 - x1, y2 - y1);
                if (layer && g.startsWith("message:") && len > 0 && len < V_STROKE_MAX) {
                    List<double[]> s = new ArrayList<>();
                    for (int i = 0; i <= 20; i++) {
                        s.add(new double[] {x1 + (x2 - x1) * i / 20.0, y1 + (y2 - y1) * i / 20.0});
                    }
                    out.add(new Head(g + " open-V stroke", s, new double[] {
                        Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)}));
                }
            }
            default -> { }
        }
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e) {
                walk(e, g, svg, out);
            }
        }
    }

    /// Strictly interior barycentric samples (no point on an edge, where a cut-out's boundary lies).
    private static List<double[]> triangleSamples(double[] v) {
        List<double[]> out = new ArrayList<>();
        int n = 12;
        for (int i = 1; i < n; i++) {
            for (int j = 1; i + j < n; j++) {
                double a = i / (double) n;
                double b = j / (double) n;
                double c = 1 - a - b;
                out.add(new double[] {a * v[0] + b * v[2] + c * v[4], a * v[1] + b * v[3] + c * v[5]});
            }
        }
        return out;
    }

    /// A rect plate covers a point strictly inside it; a path plate covers a point whose non-zero
    /// winding over all its `M ... Z` rings is not zero.
    private static boolean covers(Plate p, double[] s) {
        if ("rect".equals(p.el().getLocalName())) {
            double[] b = p.box();
            return s[0] > b[0] && s[0] < b[2] && s[1] > b[1] && s[1] < b[3];
        }
        int w = 0;
        for (String ring : p.el().getAttribute("d").split("(?=M )")) {
            List<Double> n = nums(ring);
            for (int i = 0; i + 1 < n.size(); i += 2) {
                double ax = n.get(i);
                double ay = n.get(i + 1);
                int k = (i + 2) % n.size();
                double bx = n.get(k);
                double by = n.get(k + 1);
                if (ay <= s[1]) {
                    if (by > s[1] && cross(ax, ay, bx, by, s) > 0) {
                        w++;
                    }
                } else if (by <= s[1] && cross(ax, ay, bx, by, s) < 0) {
                    w--;
                }
            }
        }
        return w != 0;
    }

    private static double cross(double ax, double ay, double bx, double by, double[] s) {
        return (bx - ax) * (s[1] - ay) - (s[0] - ax) * (by - ay);
    }

    private static boolean overlaps(double[] a, double[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    private static List<Double> nums(String d) {
        List<Double> out = new ArrayList<>();
        Matcher m = NUM.matcher(d);
        while (m.find()) {
            out.add(Double.parseDouble(m.group()));
        }
        return out;
    }

    private static double[] box(String d) {
        List<Double> n = nums(d);
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i + 1 < n.size(); i += 2) {
            b[0] = Math.min(b[0], n.get(i));
            b[1] = Math.min(b[1], n.get(i + 1));
            b[2] = Math.max(b[2], n.get(i));
            b[3] = Math.max(b[3], n.get(i + 1));
        }
        return b;
    }

    private static double num(Element el, String attr) {
        String v = el.getAttribute(attr);
        return v.isEmpty() ? 0 : Double.parseDouble(v);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = LabelPlateArrowheadGoldenTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static org.w3c.dom.Document parse(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }
}
