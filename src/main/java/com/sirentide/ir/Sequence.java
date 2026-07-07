package com.sirentide.ir;

import java.util.List;

/// A sequence diagram: actors across the top, each with a vertical lifeline, and time-ordered
/// {@link SeqMessage}s flowing between them (docs/DESIGN.md §M1 — the founding go/no-go
/// demonstrator). The SIXTH diagram type and mermaid's #2 most-used. M1 is the minimal linear form:
/// actor heads, lifelines, and arrowed labeled messages laid out by pure grid arithmetic (no graph
/// solve). FOLLOW-UP (M2): activation bars, alt/loop/par frames, bottom actor boxes.
///
/// `actors` are in first-seen declaration order (both endpoints of every message register). `messages`
/// are in declaration order (top→down). `textColor` fills the MESSAGE labels (they sit on the page
/// background) — defaults to `currentColor` so they inherit the host page's text colour (legible on
/// light AND dark). Actor-HEAD labels no longer use `textColor`: they contrast against the head box
/// fill so they stay legible on any theme.
///
/// `nodeColor` is the header `nodecolor=#hex` actor-head fill — a canonical `#rrggbb`, or `null` for
/// the built-in `#dbe4ff` (per-actor colours are a follow-up; no actor-decl syntax exists yet).
public record Sequence(List<String> actors, List<SeqMessage> messages, String textColor, String nodeColor)
    implements Diagram {

    public Sequence {
        actors = List.copyOf(actors);
        messages = List.copyOf(messages);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// No header node colour — keeps a caller that supplies just actors+messages+textColor unchanged.
    public Sequence(List<String> actors, List<SeqMessage> messages, String textColor) {
        this(actors, messages, textColor, null);
    }

    /// Default construction (`currentColor` message labels, default head fill) — keeps a caller that
    /// builds a Sequence from just its actors+messages unchanged.
    public Sequence(List<String> actors, List<SeqMessage> messages) {
        this(actors, messages, "currentColor", null);
    }
}
