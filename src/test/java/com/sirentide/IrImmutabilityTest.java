package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.FramesResult;
import com.sirentide.api.Outcome;
import com.sirentide.ir.Heatmap;
import com.sirentide.ir.Matrix;
import com.sirentide.ir.Slice;
import com.sirentide.ir.XyChart;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Mutation regressions for IR values whose record components contain mutable collections or arrays.
class IrImmutabilityTest {

    @Test
    void matrixCopiesInColumnsRowsAndNestedCells() {
        List<String> columns = new ArrayList<>(List.of("before"));
        List<Matrix.Cell> cells = new ArrayList<>(
            List.of(new Matrix.Cell("pass", Matrix.Verdict.PASS)));
        List<Matrix.Row> rows = new ArrayList<>(List.of(new Matrix.Row("row", cells)));

        Matrix matrix = new Matrix(columns, rows, "currentColor");
        columns.set(0, "after");
        rows.clear();
        cells.set(0, new Matrix.Cell("fail", Matrix.Verdict.FAIL));

        assertEquals(List.of("before"), matrix.columns());
        assertEquals(1, matrix.rows().size());
        assertEquals(new Matrix.Cell("pass", Matrix.Verdict.PASS),
            matrix.rows().get(0).cells().get(0));
    }

    @Test
    void matrixCopiesOutColumnsRowsAndNestedCells() {
        Matrix matrix = new Matrix(
            List.of("column"),
            List.of(new Matrix.Row("row",
                List.of(new Matrix.Cell("pass", Matrix.Verdict.PASS)))),
            "currentColor");

        assertThrows(UnsupportedOperationException.class,
            () -> matrix.columns().set(0, "mutated"));
        assertThrows(UnsupportedOperationException.class, () -> matrix.rows().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> matrix.rows().get(0).cells().set(
                0, new Matrix.Cell("fail", Matrix.Verdict.FAIL)));
        assertEquals("column", matrix.columns().get(0));
        assertEquals(Matrix.Verdict.PASS, matrix.rows().get(0).cells().get(0).verdict());
    }

    @Test
    void matrixPreservesNullListReferences() {
        Matrix matrix = new Matrix(null, null, "currentColor");
        Matrix.Row row = new Matrix.Row("row", null);

        assertNull(matrix.columns());
        assertNull(matrix.rows());
        assertNull(row.cells());
    }

    @Test
    void matrixPreservesNullElementsInUnmodifiableSnapshots() {
        List<String> columns = new ArrayList<>();
        columns.add(null);
        List<Matrix.Cell> cells = new ArrayList<>();
        cells.add(null);
        Matrix.Row row = new Matrix.Row("row", cells);
        List<Matrix.Row> rows = new ArrayList<>();
        rows.add(row);
        rows.add(null);

        Matrix matrix = new Matrix(columns, rows, "currentColor");
        columns.set(0, "mutated");
        cells.set(0, new Matrix.Cell("pass", Matrix.Verdict.PASS));
        rows.clear();

        assertNull(matrix.columns().get(0));
        assertNull(matrix.rows().get(1));
        assertNull(matrix.rows().get(0).cells().get(0));
        assertThrows(UnsupportedOperationException.class,
            () -> matrix.columns().set(0, "mutated again"));
        assertThrows(UnsupportedOperationException.class, () -> matrix.rows().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> matrix.rows().get(0).cells().clear());
    }

    @Test
    void heatmapCopiesInColumnsRowsAndNestedCells() {
        List<String> columns = new ArrayList<>(List.of("before"));
        List<Heatmap.Cell> cells = new ArrayList<>(
            List.of(new Heatmap.Cell("cold", 0.1, false)));
        List<Heatmap.Row> rows = new ArrayList<>(List.of(new Heatmap.Row("row", cells)));

        Heatmap heatmap = new Heatmap(columns, rows, "currentColor", "low", "high");
        columns.set(0, "after");
        rows.clear();
        cells.set(0, new Heatmap.Cell("hot", 0.9, false));

        assertEquals(List.of("before"), heatmap.columns());
        assertEquals(1, heatmap.rows().size());
        assertEquals(new Heatmap.Cell("cold", 0.1, false),
            heatmap.rows().get(0).cells().get(0));
    }

    @Test
    void heatmapCopiesOutColumnsRowsAndNestedCells() {
        Heatmap heatmap = new Heatmap(
            List.of("column"),
            List.of(new Heatmap.Row("row", List.of(new Heatmap.Cell("cold", 0.1, false)))),
            "currentColor",
            "low",
            "high");

        assertThrows(UnsupportedOperationException.class,
            () -> heatmap.columns().set(0, "mutated"));
        assertThrows(UnsupportedOperationException.class, () -> heatmap.rows().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> heatmap.rows().get(0).cells().set(0, new Heatmap.Cell("hot", 0.9, false)));
        assertEquals("column", heatmap.columns().get(0));
        assertEquals(0.1, heatmap.rows().get(0).cells().get(0).value());
    }

    @Test
    void heatmapPreservesNullListReferences() {
        Heatmap heatmap = new Heatmap(null, null, "currentColor", "low", "high");
        Heatmap.Row row = new Heatmap.Row("row", null);

        assertNull(heatmap.columns());
        assertNull(heatmap.rows());
        assertNull(row.cells());
    }

    @Test
    void heatmapPreservesNullElementsInUnmodifiableSnapshots() {
        List<String> columns = new ArrayList<>();
        columns.add(null);
        List<Heatmap.Cell> cells = new ArrayList<>();
        cells.add(null);
        Heatmap.Row row = new Heatmap.Row("row", cells);
        List<Heatmap.Row> rows = new ArrayList<>();
        rows.add(row);
        rows.add(null);

        Heatmap heatmap = new Heatmap(columns, rows, "currentColor", "low", "high");
        columns.set(0, "mutated");
        cells.set(0, new Heatmap.Cell("hot", 0.9, false));
        rows.clear();

        assertNull(heatmap.columns().get(0));
        assertNull(heatmap.rows().get(1));
        assertNull(heatmap.rows().get(0).cells().get(0));
        assertThrows(UnsupportedOperationException.class,
            () -> heatmap.columns().set(0, "mutated again"));
        assertThrows(UnsupportedOperationException.class, () -> heatmap.rows().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> heatmap.rows().get(0).cells().clear());
    }

    @Test
    void xyChartCopiesInEveryNestedSeriesArray() {
        double[] first = {1, 2};
        double[] second = {3, 4};
        List<double[]> series = new ArrayList<>(List.of(first, second));
        XyChart chart = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0)),
            series,
            List.of("one", "two"),
            "line",
            true,
            "currentColor");

        first[0] = 10;
        second[1] = 40;
        series.clear();

        assertArrayEquals(new double[] {1, 2}, chart.series().get(0));
        assertArrayEquals(new double[] {3, 4}, chart.series().get(1));
    }

    @Test
    void xyChartCopiesOutEveryNestedSeriesArray() {
        XyChart chart = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0)),
            List.of(new double[] {1, 2}, new double[] {3, 4}),
            List.of("one", "two"),
            "line",
            true,
            "currentColor");

        List<double[]> exposed = chart.series();
        exposed.get(0)[0] = 10;
        exposed.get(1)[1] = 40;
        assertThrows(UnsupportedOperationException.class, exposed::clear);

        assertArrayEquals(new double[] {1, 2}, chart.series().get(0));
        assertArrayEquals(new double[] {3, 4}, chart.series().get(1));
    }

    @Test
    void xyChartPreservesRaggedAndZeroLengthSeriesArrays() {
        XyChart chart = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0), new Slice("C", 0)),
            List.of(new double[0], new double[] {1}, new double[] {2, 3}),
            List.of("one", "two"),
            "line",
            true,
            "currentColor");

        assertArrayEquals(new double[0], chart.series().get(0));
        assertArrayEquals(new double[] {1}, chart.series().get(1));
        assertArrayEquals(new double[] {2, 3}, chart.series().get(2));
    }

    @Test
    void xyChartCopiesRepeatedAliasedArraysIndependently() {
        double[] shared = {5, 6};
        XyChart chart = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0)),
            List.of(shared, shared),
            List.of("one", "two"),
            "line",
            true,
            "currentColor");
        shared[0] = 50;

        List<double[]> exposed = chart.series();
        assertNotSame(exposed.get(0), exposed.get(1));
        assertArrayEquals(new double[] {5, 6}, exposed.get(0));
        assertArrayEquals(new double[] {5, 6}, exposed.get(1));
        exposed.get(0)[1] = 60;
        assertArrayEquals(new double[] {5, 6}, exposed.get(1));
        assertArrayEquals(new double[] {5, 6}, chart.series().get(0));
    }

    @Test
    void xyChartStillRejectsANullSeriesArray() {
        List<double[]> series = new ArrayList<>();
        series.add(null);

        assertThrows(NullPointerException.class, () -> new XyChart(
            List.of(new Slice("A", 0)),
            series,
            null,
            "line",
            false,
            "currentColor"));
    }

    @Test
    void xyChartReconstructionHasEqualValueAndHash() {
        XyChart original = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0)),
            List.of(new double[] {1, 2}, new double[] {3}),
            List.of("one", "two"),
            "line",
            true,
            "#123456");
        XyChart reconstructed = new XyChart(
            original.bars(),
            original.series(),
            original.seriesNames(),
            original.mode(),
            original.legend(),
            original.textColor());

        assertEquals(original, reconstructed);
        assertEquals(reconstructed, original);
        assertEquals(original.hashCode(), reconstructed.hashCode());
    }

    @Test
    void xyChartsWithEqualContentInDistinctArraysAreEqual() {
        XyChart left = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0)),
            List.of(new double[] {1, 2}, new double[] {3, 4}),
            List.of("one", "two"),
            "line",
            true,
            "currentColor");
        XyChart right = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0)),
            List.of(new double[] {1, 2}, new double[] {3, 4}),
            List.of("one", "two"),
            "line",
            true,
            "currentColor");

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void xyChartValueEqualityIncludesEveryRecordComponent() {
        List<Slice> bars = List.of(new Slice("A", 0), new Slice("B", 0));
        List<double[]> series = List.of(new double[] {1, 2}, new double[] {3, 4});
        List<String> names = List.of("one", "two");
        XyChart baseline = new XyChart(
            bars, series, names, "line", true, "currentColor");

        assertNotEquals(baseline, new XyChart(
            List.of(new Slice("changed", 0), new Slice("B", 0)),
            series, names, "line", true, "currentColor"));
        assertNotEquals(baseline, new XyChart(
            bars, List.of(new double[] {1, 2}, new double[] {3, 5}),
            names, "line", true, "currentColor"));
        assertNotEquals(baseline, new XyChart(
            bars, series, List.of("changed", "two"), "line", true, "currentColor"));
        assertNotEquals(baseline, new XyChart(
            bars, series, names, "scatter", true, "currentColor"));
        assertNotEquals(baseline, new XyChart(
            bars, series, names, "line", false, "currentColor"));
        assertNotEquals(baseline, new XyChart(
            bars, series, names, "line", true, "#123456"));
    }

    @Test
    void xyChartEqualityHandlesRaggedAndZeroLengthArrays() {
        XyChart left = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0), new Slice("C", 0)),
            List.of(new double[0], new double[] {1}, new double[] {2, 3}),
            List.of("one", "two"),
            "line",
            true,
            "currentColor");
        XyChart right = new XyChart(
            List.of(new Slice("A", 0), new Slice("B", 0), new Slice("C", 0)),
            List.of(new double[0], new double[] {1}, new double[] {2, 3}),
            List.of("one", "two"),
            "line",
            true,
            "currentColor");

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, new XyChart(
            right.bars(),
            List.of(new double[0], new double[] {1}, new double[] {2, 3, 0}),
            right.seriesNames(),
            right.mode(),
            right.legend(),
            right.textColor()));
    }

    @Test
    void xyChartPreservesTheLegacyNullSeriesPathAndDefaults() {
        XyChart chart = new XyChart(
            List.of(new Slice("A", 1)),
            null,
            null,
            null,
            false,
            null);

        assertNull(chart.series());
        assertNull(chart.seriesNames());
        assertEquals("bars", chart.mode());
        assertFalse(chart.legend());
        assertEquals("currentColor", chart.textColor());
    }

    @Test
    void xyChartEqualityHandlesLegacyNullSeries() {
        XyChart left = new XyChart(List.of(new Slice("A", 1)));
        XyChart right = new XyChart(
            left.bars(),
            null,
            null,
            "bars",
            false,
            "currentColor");

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, new XyChart(
            left.bars(),
            List.of(new double[] {1}),
            null,
            "bars",
            false,
            "currentColor"));
    }

    @Test
    void framesResultCopiesInFrames() {
        List<String> frames = new ArrayList<>(List.of("<svg/>"));
        FramesResult result = new FramesResult(frames, okDiagnostics());

        frames.set(0, "mutated");

        assertEquals(List.of("<svg/>"), result.frames());
    }

    @Test
    void framesResultCopiesOutFrames() {
        FramesResult result = new FramesResult(
            List.of("<svg/>"),
            okDiagnostics());

        assertThrows(UnsupportedOperationException.class,
            () -> result.frames().set(0, "mutated"));
        assertEquals(List.of("<svg/>"), result.frames());
    }

    @Test
    void framesResultPreservesANullFramesReference() {
        assertNull(new FramesResult(null, okDiagnostics()).frames());
    }

    @Test
    void framesResultPreservesNullElementsInAnUnmodifiableSnapshot() {
        List<String> frames = new ArrayList<>();
        frames.add(null);
        FramesResult result = new FramesResult(frames, okDiagnostics());
        frames.set(0, "mutated");

        assertNull(result.frames().get(0));
        assertThrows(UnsupportedOperationException.class,
            () -> result.frames().set(0, "mutated again"));
    }

    private static Diagnostics okDiagnostics() {
        return new Diagnostics(Outcome.OK, "emit", "ok", -1, "");
    }
}
