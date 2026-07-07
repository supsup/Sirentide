package com.sirentide.ir;

import java.util.List;

/// A sequence diagram: actors across the top, each with a vertical lifeline, and time-ordered
/// {@link SeqMessage}s flowing between them (docs/DESIGN.md §M1 — the founding go/no-go
/// demonstrator). The SIXTH diagram type and mermaid's #2 most-used. M1 is the minimal linear form:
/// actor heads, lifelines, and arrowed labeled messages laid out by pure grid arithmetic (no graph
/// solve). FOLLOW-UP (M2): activation bars, alt/loop/par frames, bottom actor boxes.
///
/// `actors` are in first-seen declaration order (both endpoints of every message register). `messages`
/// are in declaration order (top→down). `textColor` fills every label — defaults to `currentColor`
/// so labels inherit the host page's text colour (legible on light AND dark), matching the other types.
public record Sequence(List<String> actors, List<SeqMessage> messages, String textColor)
    implements Diagram {

    public Sequence {
        actors = List.copyOf(actors);
        messages = List.copyOf(messages);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// Default construction (`currentColor` labels) — keeps a caller that builds a Sequence from just
    /// its actors+messages unchanged.
    public Sequence(List<String> actors, List<SeqMessage> messages) {
        this(actors, messages, "currentColor");
    }
}
