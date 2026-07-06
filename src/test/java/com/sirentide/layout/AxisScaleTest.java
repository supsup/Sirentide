package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Unit + golden-tick tests for the pure {@link AxisScale} primitive (Confluence's explicit ask: a
/// "pure module w/ golden tick tests"). Proportional projection incl. a min > 0 domain, 1-2-5 tick
/// stops at several ranges, ISO-date-domain projection, and a degenerate `[x, x]` domain that must
/// never divide by zero.
class AxisScaleTest {

    private static final double EPS = 1e-9;

    @Test
    void projectsProportionally() {
        AxisScale a = new AxisScale(0, 10);
        assertEquals(0, a.project(0, 0, 100), EPS);
        assertEquals(50, a.project(5, 0, 100), EPS);
        assertEquals(100, a.project(10, 0, 100), EPS);
    }

    @Test
    void projectsIsMinNormalizedForAMinAboveZeroDomain() {
        // The bug #2 root: a domain that does NOT start at 0. project() subtracts min.
        AxisScale a = new AxisScale(100, 200);
        assertEquals(0, a.project(100, 0, 100), EPS);
        assertEquals(50, a.project(150, 0, 100), EPS);
        assertEquals(100, a.project(200, 0, 100), EPS);
    }

    @Test
    void projectsOntoAnInvertedPixelSpanForAYAxis() {
        // pixelStart > pixelEnd (screen y grows downward): min sits at the bottom, max at the top.
        AxisScale a = new AxisScale(0, 8);
        assertEquals(200, a.project(0, 200, 20), EPS);   // domain min -> plot bottom
        assertEquals(20, a.project(8, 200, 20), EPS);    // domain max -> plot top
    }

    @Test
    void constructorOrdersTheDomainAndComputesBothEnds() {
        AxisScale swapped = new AxisScale(200, 100);
        assertEquals(100, swapped.min(), EPS);
        assertEquals(200, swapped.max(), EPS);
        AxisScale fromValues = AxisScale.of(7, 2, 5, 9, 3);
        assertEquals(2, fromValues.min(), EPS);
        assertEquals(9, fromValues.max(), EPS);
    }

    @Test
    void niceTickStops_0to10() {
        assertEquals(List.of(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), new AxisScale(0, 10).ticks());
    }

    @Test
    void niceTickStops_0to100() {
        assertEquals(List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), new AxisScale(0, 100).ticks());
    }

    @Test
    void niceTickStops_3to27_stayInsideDomain() {
        List<Double> ticks = new AxisScale(3, 27).ticks();
        assertEquals(List.of(5.0, 10.0, 15.0, 20.0, 25.0), ticks);
        for (double t : ticks) {
            assertTrue(t >= 3 && t <= 27, "tick " + t + " inside [3,27]");
        }
    }

    @Test
    void dateDomainProjectsProportionallyInDays() {
        double d2020 = AxisScale.parseDomainValue("2020-01-01");
        double d2021 = AxisScale.parseDomainValue("2021-01-01");
        assertEquals(LocalDate.of(2020, 1, 1).toEpochDay(), d2020, EPS);
        AxisScale a = new AxisScale(d2020, d2021);
        assertEquals(0, a.project(d2020, 0, 366), EPS);
        assertEquals(366, a.project(d2021, 0, 366), EPS);   // 2020 is a leap year: 366 days
        // A mid-year date lands proportionally between the ends.
        double dMid = AxisScale.parseDomainValue("2020-07-01");
        double mid = a.project(dMid, 0, 366);
        assertTrue(mid > 150 && mid < 220, "2020-07-01 lands mid-span, got " + mid);
    }

    @Test
    void bareYearStaysNumericAndYearMonthParses() {
        assertEquals(2020.0, AxisScale.parseDomainValue("2020"), EPS);   // human-readable, proportional
        assertEquals(5.5, AxisScale.parseDomainValue("5.5"), EPS);
        assertEquals(LocalDate.of(2020, 6, 1).toEpochDay(),
            AxisScale.parseDomainValue("2020-06"), EPS);
    }

    @Test
    void unparseableTokenThrowsSoTheParserCanSkipTheRow() {
        assertThrows(NumberFormatException.class, () -> AxisScale.parseDomainValue("notanumber"));
        assertThrows(NumberFormatException.class, () -> AxisScale.parseDomainValue("2020-13-40"));
    }

    @Test
    void degenerateDomainNeverDividesByZero() {
        AxisScale a = new AxisScale(5, 5);
        assertEquals(0, a.span(), EPS);
        assertEquals(50, a.project(5, 0, 100), EPS);     // collapses to the pixel midpoint
        assertEquals(50, a.project(999, 0, 100), EPS);   // any value, still no NaN/Infinity
        assertTrue(Double.isFinite(a.project(5, 0, 100)));
        assertEquals(List.of(5.0), a.ticks());
    }
}
