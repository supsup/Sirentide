package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The xychart COLOUR-BINDING pins (Confluence, batch-2 review sirentide/35).
/// Two delete-mutants survived the whole suite because nothing asserted on the
/// colour DIMENSION of the emitted output (only counts/positions):
///   MUT-E — every series colour → PALETTE[0]: a 3-series line chart renders
///           indistinguishable series, suite green (XyChartLayout ~:280).
///   MUT-D — every legend swatch → PALETTE[0]: the key stops identifying
///           anything, suite green (XyChartLayout ~:321).
/// These pins kill both. Palette: [0]=#4e79a7, [1]=#f28e2b. No color= header
/// (that intentionally forces one colour); the palette assigns per series.
class XyChartColourBindingTest {

    private static Set<String> hexOf(String svg, String pattern) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(pattern).matcher(svg);
        while (m.find()) { out.add(m.group(1)); }
        return out;
    }

    @Test
    void multiSeriesGetDistinctPaletteColours() {
        // 2-series line chart, NO legend → series segments carry the palette
        String svg = Sirentide.render("xychart line\n\"a\" : 1 2\n\"b\" : 3 1\n");
        Set<String> strokes = hexOf(svg, "<line[^>]*stroke=\"(#[0-9a-f]{6})\"");
        assertTrue(strokes.contains("#4e79a7"), "series 0 must be PALETTE[0]: " + strokes);
        assertTrue(strokes.contains("#f28e2b"),
            "series 1 must be PALETTE[1], not collapsed onto PALETTE[0] (MUT-E): " + strokes);
    }

    @Test
    void legendSwatchesBindToTheirSeriesColour() {
        // 2-series line + legend → swatches are the only <rect> in line mode
        String svg = Sirentide.render(
            "xychart line legend\nseries: Alpha, Beta\n\"a\" : 1 2\n\"b\" : 3 1\n");
        Set<String> swatchFills = hexOf(svg, "<rect[^>]*fill=\"(#[0-9a-f]{6})\"");
        Set<String> seriesStrokes = hexOf(svg, "<line[^>]*stroke=\"(#[0-9a-f]{6})\"");
        // both palette colours present as swatches (MUT-D collapses to one)
        assertTrue(swatchFills.contains("#4e79a7") && swatchFills.contains("#f28e2b"),
            "each series needs its own swatch colour (MUT-D): " + swatchFills);
        // and the swatch colours are exactly the series colours — the binding
        assertTrue(seriesStrokes.containsAll(swatchFills),
            "every swatch colour must be a real series colour (binding): swatches "
                + swatchFills + " vs series " + seriesStrokes);
    }
}
