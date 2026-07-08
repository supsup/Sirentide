package com.sirentide.ir;

import java.util.List;

/// Sirentide's thirteenth diagram type: a mermaid-style `journey` — a user-journey satisfaction map.
/// An OPTIONAL `title`, then `section` groups, each holding tasks `<name>: <score 1-5>: <actor>[, …]`.
/// Layout ({@link com.sirentide.layout.JourneyLayout}) places the tasks in order along the x-axis, maps
/// each task's score onto a 1..5 satisfaction y-axis (HIGHER score sits HIGHER), connects the task
/// points with a single line, brackets each section's task span with a header, and lists each task's
/// actors beneath its point.
///
/// The IR is `title` (null when absent) + the ordered {@link JourneySection} list (each with ordered
/// {@link JourneyTask}s). The malformed decisions live in the parser/layout, never fail the bake
/// (docs/DESIGN.md §6): a task with no numeric score is dropped; a task before any `section` is
/// dropped; an out-of-range score clamps into 1..5 (in {@link JourneyTask}); an empty journey lays out
/// to a minimal inert canvas (still a journey — round-trips, never the 0×0 shell).
///
/// `textColor` fills the page-background text (title, tick labels, task/actor labels); default
/// `currentColor` so it inherits the host page's text colour. Layout dispatch is
/// `case Journey j -> JourneyLayout.layout(j, math)`.
public record Journey(String title, List<JourneySection> sections, String textColor) implements Diagram {

    public Journey {
        sections = List.copyOf(sections);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction with the `currentColor` text fill — keeps tests that build a `Journey`
    /// from just a title + sections unchanged.
    public Journey(String title, List<JourneySection> sections) {
        this(title, sections, "currentColor");
    }
}
