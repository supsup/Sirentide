package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Slice;
import com.sirentide.ir.Timeline;
import com.sirentide.parse.DslParser;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/// Sirentide's third diagram type: a timeline renders an axis (line), an event dot per row
/// (full-circle disc), and a label + value per event as glyph paths.
class TimelineTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /// Drop the a11y `<title>…</title><desc>…</desc>` block so GEOMETRY-token counts (e.g. the arc
    /// letter `" A "`) aren't polluted by natural-language a11y text — a label like "Series A"
    /// legitimately contains `" A "` in the desc, which is not an arc command.
    private static String geometry(String svg) {
        int t = svg.indexOf("<title>");
        int d = svg.indexOf("</desc>");
        if (t >= 0 && d > t) {
            return svg.substring(0, t) + svg.substring(d + "</desc>".length());
        }
        return svg;
    }

    @Test
    void a2_isoDateEventsRememberTheirDateForDisplay() {
        // REGRESSION (A2, deep-review sirentide/14): an ISO date parses to an opaque epoch-day for
        // placement; the Slice used to keep only that double, so the label printed the epoch number
        // (e.g. 18276) instead of the date. The Slice now carries the original date token in
        // valueLabel; a bare year / plain number keeps valueLabel null and formats numerically.
        Timeline t = (Timeline) DslParser.parse("""
            timeline
              "Launch"  : 2020-01-15
              "Review"  : 2020-06
              "Founded" : 2019
            """);
        Slice dated = t.events().get(0);
        assertEquals("2020-01-15", dated.valueLabel(), "the full ISO date is the display label");
        assertEquals(LocalDate.of(2020, 1, 15).toEpochDay(), (long) dated.value(), "placement stays epoch-day");

        Slice yearMonth = t.events().get(1);
        assertEquals("2020-06", yearMonth.valueLabel(), "year-month shows as the author wrote it");

        Slice bareYear = t.events().get(2);
        assertNull(bareYear.valueLabel(), "a bare year is a plain number — no date label");
        assertEquals(2019.0, bareYear.value(), 1e-9, "and its numeric value is the year itself");
    }

    @Test
    void axisDotsAndLabels() {
        String svg = Sirentide.render("""
            timeline
              "Founded"  : 2020
              "Series A" : 2021
              "Launch"   : 2023
            """);
        String geom = geometry(svg);
        assertEquals(1, count(geom, "<line"), "one horizontal axis");
        assertEquals(6, count(geom, " A "), "3 event dots, each a two-arc disc");
        assertEquals(9, count(geom, "<path"), "3 dots + 3 labels + 3 values");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
    }

    // Contract-cleanliness is enforced by ContainmentTest's allowlist guard (covers timeline too).

    @Test
    void renderIsDeterministic() {
        String dsl = "timeline\n \"A\" : 1\n \"B\" : 2\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void emptyTimelineDrawsOnlyTheAxis() {
        String svg = Sirentide.render("timeline\n");
        assertEquals(1, count(svg, "<line"), "axis still drawn");
        assertEquals(0, count(svg, " A "), "no event dots");
    }
}
