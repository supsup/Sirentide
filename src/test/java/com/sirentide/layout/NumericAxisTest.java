package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.font.FontMetrics;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

/// The numeric xychart axis (plan c880b12e, numeric-x slice): nice 1/2/5 x 10^k steps, thinning so
/// tick labels never overlap, and plain-decimal labels that never fall into Java's E-notation.
class NumericAxisTest {

    private static final FontMetrics FONT = FontMetrics.bundled();
    private static final double SIZE = 9;
    private static final ToDoubleFunction<String> WIDTH = s -> FONT.runWidth(s, SIZE);

    private static List<String> labels(NumericAxis.Fit fit) {
        return fit.placed().stream().filter(NumericAxis.Placed::labelled)
            .map(p -> p.tick().label()).toList();
    }

    // ---- formatting -------------------------------------------------------------------------

    @Test
    void aTinyStepPrintsAsPlainDecimalNeverENotation() {
        // Double.toString(5.0E-4) is "5.0E-4"; the axis must say 0.0005.
        assertEquals("0.0005", NumericAxis.format(1, 5, -4));
        assertEquals("0.0002", NumericAxis.format(1, 2, -4));
        assertEquals("0.0000001", NumericAxis.format(1, 1, -7));
    }

    @Test
    void aHugeStepPrintsAsPlainDecimalNeverENotation() {
        assertEquals("200000000000", NumericAxis.format(1, 2, 11));
        assertEquals("10000000", NumericAxis.format(1, 1, 7));
    }

    @Test
    void trailingZerosAndNegativeZeroAreStripped() {
        assertEquals("0", NumericAxis.format(0, 5, -1));
        assertEquals("1", NumericAxis.format(2, 5, -1));
        assertEquals("1.5", NumericAxis.format(3, 5, -1));
        assertEquals("-12", NumericAxis.format(-6, 2, 0));
    }

    @Test
    void aLargeOffsetIsLabelledExactlyNotWithFloatDust() {
        // 1e15 + k*0.2 would print as 1.0000000000000002E15-style dust through double arithmetic.
        NumericAxis.Fit fit = NumericAxis.fitHorizontal(1e15, 1e15 + 1, 0, 2000, 0, 2000, WIDTH);
        for (String l : labels(fit)) {
            assertTrue(l.matches("1000000000000000(\\.\\d+)?|1000000000000001"), l);
        }
        assertFalse(labels(fit).isEmpty(), "control: some tick was chosen");
    }

    @Test
    void noTickLabelUsesENotationAcrossTwentyFourDecades() {
        for (int e = -12; e <= 12; e++) {
            double hi = 7 * Math.pow(10, e);
            for (NumericAxis.Fit fit : List.of(
                    NumericAxis.fitHorizontal(0, hi, 40, 460, 0, 480, WIDTH),
                    NumericAxis.fitVertical(-hi, hi, 260, 20, SIZE))) {
                assertFalse(fit.placed().isEmpty(), "control: decade " + e + " chose ticks");
                for (NumericAxis.Placed p : fit.placed()) {
                    assertFalse(p.tick().label().contains("E") || p.tick().label().contains("e"),
                        "decade " + e + " printed " + p.tick().label());
                    assertEquals(p.tick().value(), Double.parseDouble(p.tick().label()), Math.abs(hi) * 1e-12,
                        "the label is the value it sits at");
                }
            }
        }
    }

    // ---- tick selection + thinning ----------------------------------------------------------

    @Test
    void theBarkerLagAxisThinsToEveryOtherLagAndNoLabelOverlaps() {
        // 25 lags -12..12, padded 3%, across a ~410px plot: step 1 would need 25 labels; it does not fit.
        NumericAxis.Fit fit = NumericAxis.fitHorizontal(-12.72, 12.72, 60, 456, 0, 480, WIDTH);
        assertEquals(List.of("-12", "-10", "-8", "-6", "-4", "-2", "0", "2", "4", "6", "8", "10", "12"),
            labels(fit));
        assertTrue(fit.dropped().isEmpty(), "thinning chose the step, nothing was dropped");
        assertDisjoint(fit);
    }

    @Test
    void aWidePlotKeepsTheFinestStep() {
        // At 2000px a step of 1 fits, and so does the finer 0.5 (39px apart): the finest fit wins.
        NumericAxis.Fit fit = NumericAxis.fitHorizontal(-12.72, 12.72, 0, 2000, 0, 2000, WIDTH);
        assertEquals(51, labels(fit).size(), "step 0.5 at 2000px: " + labels(fit));
        for (int lag = -12; lag <= 12; lag++) {
            assertTrue(labels(fit).contains(Integer.toString(lag)), "lag " + lag + " in " + labels(fit));
        }
        assertDisjoint(fit);
    }

    @Test
    void stepsAreOneTwoFiveTimesAPowerOfTen() {
        for (double hi : new double[] {0.7, 3, 13, 77, 140, 999, 12345, 0.0042}) {
            NumericAxis.Fit fit = NumericAxis.fitHorizontal(0, hi, 40, 460, 0, 480, WIDTH);
            List<NumericAxis.Placed> p = fit.placed();
            assertTrue(p.size() >= 2, "control: at least two ticks for [0," + hi + "]");
            double step = p.get(1).tick().value() - p.get(0).tick().value();
            double mant = step / Math.pow(10, Math.floor(Math.log10(step) + 1e-9));
            assertTrue(Math.abs(mant - 1) < 1e-6 || Math.abs(mant - 2) < 1e-6 || Math.abs(mant - 5) < 1e-6,
                "step " + step + " for [0," + hi + "]");
            assertDisjoint(fit);
        }
    }

    @Test
    void everyTickLiesInsideTheDomain() {
        NumericAxis.Fit fit = NumericAxis.fitHorizontal(-0.37, 1.91, 40, 460, 0, 480, WIDTH);
        assertTrue(fit.placed().size() >= 3, "control: ticks were chosen: " + fit);
        for (NumericAxis.Placed p : fit.placed()) {
            assertTrue(p.tick().value() >= -0.37 && p.tick().value() <= 1.91, p.tick().label());
        }
    }

    @Test
    void verticalTicksKeepTheirMinimumSpacing() {
        NumericAxis.Fit fit = NumericAxis.fitVertical(0, 100, 260, 20, SIZE);
        List<NumericAxis.Placed> p = fit.placed();
        assertTrue(p.size() >= 3, "control: several y ticks");
        for (int i = 0; i + 1 < p.size(); i++) {
            assertTrue(Math.abs(p.get(i + 1).px() - p.get(i).px()) >= NumericAxis.MIN_Y_SPACING - 1e-9,
                "y ticks " + p.get(i).tick().label() + " and " + p.get(i + 1).tick().label());
        }
    }

    @Test
    void aLabelWiderThanTheRoomIsDroppedAndReportedNotSilentlyLost() {
        // A 10px canvas cannot hold "1000" at 9px. The tick mark stays; its label is reported.
        NumericAxis.Fit fit = NumericAxis.fitHorizontal(990, 1010, 0, 10, 0, 10, WIDTH);
        assertFalse(fit.dropped().isEmpty(), "the unplaceable label is named: " + fit);
        assertTrue(labels(fit).isEmpty(), "and not drawn");
        assertFalse(fit.placed().isEmpty(), "the tick mark itself is still placed");
    }

    @Test
    void paddingADegenerateDomainNeverYieldsAZeroSpan() {
        double[] d = NumericAxis.pad(5, 5, 0.03);
        assertTrue(d[0] < 5 && d[1] > 5, "single value 5 is inset: " + d[0] + ".." + d[1]);
        double[] z = NumericAxis.pad(0, 0, 0.03);
        assertTrue(z[0] < 0 && z[1] > 0, "single value 0 is inset");
        double[] n = NumericAxis.pad(-12, 12, 0.03);
        assertEquals(-12.72, n[0], 1e-9);
        assertEquals(12.72, n[1], 1e-9);
    }

    private static void assertDisjoint(NumericAxis.Fit fit) {
        NumericAxis.Placed prev = null;
        for (NumericAxis.Placed p : fit.placed()) {
            if (!p.labelled()) {
                continue;
            }
            if (prev != null) {
                assertTrue(p.labelLeft() >= prev.labelLeft() + prev.labelWidth() + NumericAxis.LABEL_GAP - 1e-9,
                    prev.tick().label() + " and " + p.tick().label() + " overlap or crowd");
            }
            prev = p;
        }
    }
}
