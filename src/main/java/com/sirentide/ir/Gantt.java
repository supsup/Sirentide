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

    /// The earliest start across all tasks (the time-axis LEFT edge). The missing min-normalization
    /// was the invisible-sliver bug: absolute-date tasks (start ≫ 0) crammed to the right edge as
    /// ~sub-pixel bars. `0` for an empty chart.
    public double start() {
        double s = Double.POSITIVE_INFINITY;
        for (Task t : tasks) {
            s = Math.min(s, t.start());
        }
        return Double.isFinite(s) ? s : 0;
    }

    /// The latest end across all tasks (the time-axis RIGHT edge).
    public double end() {
        double e = Double.NEGATIVE_INFINITY;
        for (Task t : tasks) {
            e = Math.max(e, t.end());
        }
        return Double.isFinite(e) ? e : 0;
    }
}
