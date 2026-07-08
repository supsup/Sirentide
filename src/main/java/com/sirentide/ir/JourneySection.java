package com.sirentide.ir;

import java.util.List;

/// A named group of {@link JourneyTask}s in a user-{@link Journey}. Sections are laid out in
/// declaration order; their tasks span a contiguous run of columns on the shared satisfaction axis,
/// with the section header bracketing that run. An empty section (no tasks) contributes no columns
/// and draws no header (layout skips it).
public record JourneySection(String name, List<JourneyTask> tasks) {

    public JourneySection {
        name = name == null ? "" : name;
        tasks = List.copyOf(tasks);
    }
}
