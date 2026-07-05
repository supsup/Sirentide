package com.sirentide.ir;

import java.util.List;

/// A gantt chart: tasks as horizontal bars on a shared time axis. Layout is deterministic
/// arithmetic — task span → bar x/width, row index → bar y — no optimization.
public record Gantt(List<Task> tasks) implements Diagram {

    public Gantt {
        tasks = List.copyOf(tasks);
    }

    /// The latest end across all tasks (the time-axis extent; the width denominator).
    public double end() {
        double e = 0;
        for (Task t : tasks) {
            e = Math.max(e, t.end());
        }
        return e;
    }
}
