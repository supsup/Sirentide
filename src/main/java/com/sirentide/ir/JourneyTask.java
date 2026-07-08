package com.sirentide.ir;

import java.util.List;

/// One task in a user-{@link Journey} section: a named step, a satisfaction `score` on the 1..5 scale,
/// and the ordered `actors` who take part. The score is CLAMPED into `[1, 5]` in the compact
/// constructor so the IR invariant holds regardless of the caller (an out-of-range parse value
/// saturates to the nearest end rather than escaping the axis — docs/DESIGN.md §6). `actors` may be
/// empty (a task with no listed actor is still a task); a null list is treated as empty.
public record JourneyTask(String name, int score, List<String> actors) {

    public JourneyTask {
        name = name == null ? "" : name;
        actors = actors == null ? List.of() : List.copyOf(actors);
        score = Math.max(1, Math.min(5, score));
    }
}
