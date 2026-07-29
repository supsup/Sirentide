package com.sirentide.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Deterministic minimum-row partitioning for already-emitted horizontal label intervals.
///
/// Inputs are the ACTUAL post-clamp boxes the layout will emit, indexed by declaration order. A
/// stable left-edge ordering plus an earliest-finishing-row priority queue gives the standard
/// interval-partitioning optimum: if the row with the smallest right edge cannot clear the next
/// interval, no existing row can, so a new row is necessary. Equal edges resolve by stable row
/// index. Runtime is O(n log n), retained state O(n).
final class LabelIntervalPacker {

    private LabelIntervalPacker() {}

    private static final Pattern PATH_NUMBER = Pattern.compile(
        "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    record Box(double minX, double minY, double maxX, double maxY) {
        Box {
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                || maxX < minX || maxY < minY) {
                throw new IllegalArgumentException("invalid label box");
            }
        }

        Box union(Box other) {
            if (other == null) {
                return this;
            }
            return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY));
        }
    }

    record Result(int[] rows, int rowCount) {
        Result {
            rows = rows.clone();
            if (rowCount < 0) {
                throw new IllegalArgumentException("negative row count");
            }
        }

        @Override
        public int[] rows() {
            return rows.clone();
        }
    }

    private record Row(int index, double rightEdge) {}

    static Result pack(Box[] boxes, double gap) {
        requireInputs(boxes, gap);
        List<Integer> order = new ArrayList<>(boxes.length);
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null) {
                order.add(i);
            }
        }
        // Explicit declaration-index tie break makes the stable contract visible even if the sort
        // implementation changes later.
        order.sort(Comparator.comparingDouble((Integer i) -> boxes[i].minX())
            .thenComparingInt(Integer::intValue));

        PriorityQueue<Row> rowsByRight = new PriorityQueue<>(
            Comparator.comparingDouble(Row::rightEdge).thenComparingInt(Row::index));
        int[] assigned = new int[boxes.length];
        Arrays.fill(assigned, -1);                 // a non-displayed label consumes no row
        int rowCount = 0;
        for (int index : order) {
            Box box = boxes[index];
            Row row = rowsByRight.peek();
            if (row != null && row.rightEdge() + gap <= box.minX()) {
                rowsByRight.remove();
            } else {
                row = new Row(rowCount++, Double.NEGATIVE_INFINITY);
            }
            assigned[index] = row.index();
            rowsByRight.add(new Row(row.index(), box.maxX()));
        }
        return new Result(assigned, rowCount);
    }

    /// Whether an existing row assignment already clears every actual interval by {@code gap}.
    /// Used as the byte-compatibility gate before invoking {@link #pack}.
    static boolean rowsClean(Box[] boxes, int[] rows, double gap) {
        requireInputs(boxes, gap);
        if (rows == null || rows.length != boxes.length) {
            throw new IllegalArgumentException("row assignment length mismatch");
        }
        List<Integer> order = new ArrayList<>(boxes.length);
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null) {
                if (rows[i] < 0) {
                    throw new IllegalArgumentException("displayed label has no row");
                }
                order.add(i);
            }
        }
        order.sort(Comparator.comparingInt((Integer i) -> rows[i])
            .thenComparingDouble(i -> boxes[i].minX())
            .thenComparingInt(Integer::intValue));
        int currentRow = -1;
        double right = Double.NEGATIVE_INFINITY;
        for (int index : order) {
            int row = rows[index];
            if (row != currentRow) {
                currentRow = row;
                right = Double.NEGATIVE_INFINITY;
            }
            Box box = boxes[index];
            if (right + gap > box.minX()) {
                return false;
            }
            right = Math.max(right, box.maxX());
        }
        return true;
    }

    /// Whether an existing placement clears every pair of actual boxes in two dimensions. Horizontal
    /// overlap alone is harmless when the placed vertical boxes clear by {@code gap}, and vertically
    /// overlapping row envelopes are harmless when the relevant labels are horizontally remote.
    ///
    /// The same-row precheck guarantees at most one active horizontal interval per row. The sweep is
    /// therefore O(n log n + n*r), where {@code r} is the existing row count (two for Timeline's
    /// compatibility path), without a parser-scale pairwise matrix.
    static boolean placedBoxesClean(Box[] boxes, int[] rows, double[] baselines, double gap) {
        requireInputs(boxes, gap);
        if (rows == null || baselines == null || rows.length != boxes.length) {
            throw new IllegalArgumentException("placed-box input mismatch");
        }
        for (double baseline : baselines) {
            if (!Double.isFinite(baseline)) {
                throw new IllegalArgumentException("non-finite label baseline");
            }
        }
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null && (rows[i] < 0 || rows[i] >= baselines.length)) {
                throw new IllegalArgumentException("displayed label row outside baseline array");
            }
        }
        if (!rowsClean(boxes, rows, gap)) {
            return false;
        }

        List<Integer> order = new ArrayList<>(boxes.length);
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null) {
                order.add(i);
            }
        }
        order.sort(Comparator.comparingDouble((Integer i) -> boxes[i].minX())
            .thenComparingInt(Integer::intValue));

        List<Integer> active = new ArrayList<>(baselines.length);
        for (int index : order) {
            Box box = boxes[index];
            active.removeIf(previous -> boxes[previous].maxX() + gap <= box.minX());
            double minY = box.minY() + baselines[rows[index]];
            double maxY = box.maxY() + baselines[rows[index]];
            for (int previous : active) {
                Box other = boxes[previous];
                double otherMinY = other.minY() + baselines[rows[previous]];
                double otherMaxY = other.maxY() + baselines[rows[previous]];
                boolean verticallyClear = otherMaxY + gap <= minY || maxY + gap <= otherMinY;
                if (!verticallyClear) {
                    return false;
                }
            }
            active.add(index);
        }
        return true;
    }

    /// Translation needed to put an actual horizontal box inside {@code [minX,maxX]}. A box wider
    /// than the available frame is left-aligned so a caller may grow the right canvas deterministically.
    /// Returning zero preserves the exact legacy origin and emitted bytes for already-contained ink.
    static double horizontalInFrameShift(Box box, double minX, double maxX) {
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || maxX < minX) {
            throw new IllegalArgumentException("invalid horizontal frame");
        }
        if (box == null) {
            return 0;
        }
        if (box.maxX() - box.minX() > maxX - minX) {
            return minX - box.minX();
        }
        if (box.minX() < minX) {
            return minX - box.minX();
        }
        if (box.maxX() > maxX) {
            return maxX - box.maxX();
        }
        return 0;
    }

    /// Conservative compatibility check for the vertical bands of an existing assignment. It is
    /// intentionally band-based: if two row envelopes overlap, the generalized path recomputes
    /// baselines even when a particular pair happens to be horizontally disjoint.
    static boolean rowBandsDisjoint(Box[] relativeBoxes, int[] rows, double[] baselines) {
        if (relativeBoxes == null || rows == null || baselines == null
            || relativeBoxes.length != rows.length) {
            throw new IllegalArgumentException("row-band input mismatch");
        }
        double[] min = new double[baselines.length];
        double[] max = new double[baselines.length];
        Arrays.fill(min, Double.POSITIVE_INFINITY);
        Arrays.fill(max, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < relativeBoxes.length; i++) {
            Box box = relativeBoxes[i];
            if (box == null) {
                continue;
            }
            int row = rows[i];
            if (row < 0 || row >= baselines.length) {
                throw new IllegalArgumentException("row outside baseline array");
            }
            min[row] = Math.min(min[row], baselines[row] + box.minY());
            max[row] = Math.max(max[row], baselines[row] + box.maxY());
        }
        List<Integer> order = new ArrayList<>(baselines.length);
        for (int row = 0; row < baselines.length; row++) {
            if (min[row] != Double.POSITIVE_INFINITY) {
                order.add(row);
            }
        }
        order.sort(Comparator.comparingDouble((Integer row) -> min[row])
            .thenComparingInt(Integer::intValue));
        double previousBottom = Double.NEGATIVE_INFINITY;
        for (int row : order) {
            if (previousBottom > min[row]) {
                return false;
            }
            previousBottom = Math.max(previousBottom, max[row]);
        }
        return true;
    }

    /// Baselines growing downward, with exact box-envelope separation.
    static double[] baselinesDown(Box[] boxes, int[] rows, int rowCount,
                                  double firstBaseline, double verticalGap) {
        return baselines(boxes, rows, rowCount, firstBaseline, verticalGap, false);
    }

    /// Baselines growing upward, with exact box-envelope separation.
    static double[] baselinesUp(Box[] boxes, int[] rows, int rowCount,
                                double firstBaseline, double verticalGap) {
        return baselines(boxes, rows, rowCount, firstBaseline, verticalGap, true);
    }

    private static double[] baselines(Box[] boxes, int[] rows, int rowCount,
                                      double firstBaseline, double gap, boolean upward) {
        requireInputs(boxes, gap);
        if (rows == null || rows.length != boxes.length || rowCount < 0
            || !Double.isFinite(firstBaseline)) {
            throw new IllegalArgumentException("invalid baseline inputs");
        }
        if (rowCount == 0) {
            return new double[0];
        }
        double[] min = new double[rowCount];
        double[] max = new double[rowCount];
        Arrays.fill(min, Double.POSITIVE_INFINITY);
        Arrays.fill(max, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] == null) {
                continue;
            }
            int row = rows[i];
            if (row < 0 || row >= rowCount) {
                throw new IllegalArgumentException("displayed label row outside row count");
            }
            min[row] = Math.min(min[row], boxes[i].minY());
            max[row] = Math.max(max[row], boxes[i].maxY());
        }
        double[] out = new double[rowCount];
        out[0] = firstBaseline;
        for (int row = 1; row < rowCount; row++) {
            if (min[row - 1] == Double.POSITIVE_INFINITY || min[row] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("empty allocated row");
            }
            if (upward) {
                out[row] = out[row - 1] + min[row - 1] - gap - max[row];
            } else {
                out[row] = out[row - 1] + max[row - 1] + gap - min[row];
            }
        }
        return out;
    }

    /// Exact numeric-pair bounds for FontMetrics glyph path data (M/L/Q/Z command alphabet).
    static Box pathBounds(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Matcher matcher = PATH_NUMBER.matcher(path);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean sawPair = false;
        while (matcher.find()) {
            double x = Double.parseDouble(matcher.group());
            if (!matcher.find()) {
                break;
            }
            double y = Double.parseDouble(matcher.group());
            sawPair = true;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return sawPair ? new Box(minX, minY, maxX, maxY) : null;
    }

    private static void requireInputs(Box[] boxes, double gap) {
        if (boxes == null || !Double.isFinite(gap) || gap < 0) {
            throw new IllegalArgumentException("invalid interval-packing inputs");
        }
    }
}
