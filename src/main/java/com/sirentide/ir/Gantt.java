package com.sirentide.ir;

import java.util.List;

/// A gantt chart: tasks as horizontal bars on a shared time axis. Layout is deterministic
/// arithmetic — task span → bar x/width, row index → bar y — no optimization.
/// `textColor` fills the off-slice page-background text (the task-name labels). Defaults to
/// `currentColor` so it inherits the host page's text colour (legible on light AND dark); the DSL
/// `color=` modifier overrides it.
public record Gantt(List<Task> tasks, String textColor) implements Diagram {

    public Gantt {
        tasks = List.copyOf(tasks);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps existing callers/tests
    /// that build a `Gantt` from just its tasks unchanged.
    public Gantt(List<Task> tasks) {
        this(tasks, "currentColor");
    }

    /// The time-axis LEFT edge: the minimum over BOTH endpoints of EVERY task. Aggregating over the
    /// `end` as well as the `start` (SIR-09) keeps a REVERSED/out-of-order task (`end < start`, so its
    /// smaller coordinate is its `end`) inside the domain — otherwise that endpoint fell outside
    /// `[start, end]` and `AxisScale` extrapolated the bar off-canvas (invisible). The missing
    /// min-normalization was the original invisible-sliver bug (absolute-date tasks crammed to the
    /// right edge as sub-pixel bars); this closes the reversed-task escape too. `0` for an empty chart.
    public double start() {
        double s = Double.POSITIVE_INFINITY;
        for (Task t : tasks) {
            s = Math.min(s, Math.min(t.start(), t.end()));
        }
        return Double.isFinite(s) ? s : 0;
    }

    /// The time-axis RIGHT edge: the maximum over BOTH endpoints of EVERY task (symmetric to
    /// {@link #start()} — a reversed task whose larger coordinate is its `start` still bounds the
    /// domain, so its bar stays on-canvas). `0` for an empty chart.
    public double end() {
        double e = Double.NEGATIVE_INFINITY;
        for (Task t : tasks) {
            e = Math.max(e, Math.max(t.start(), t.end()));
        }
        return Double.isFinite(e) ? e : 0;
    }
}
