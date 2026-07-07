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
///
/// `bodyHadContent` distinguishes a genuinely EMPTY diagram (a bare `sequence` header, no body — an
/// intentional blank canvas) from an ERROR (a non-empty body where every line was malformed → zero
/// actors). The former stays a minimal blank canvas; the latter degrades VISIBLY (layout draws a
/// `sequence: no messages parsed` glyph-run) so a mistyped diagram never renders as silent nothing.
public record Sequence(List<String> actors, List<SeqMessage> messages, String textColor,
                       String nodeColor, boolean bodyHadContent)
    implements Diagram {

    public Sequence {
        actors = List.copyOf(actors);
        messages = List.copyOf(messages);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// No body-content signal (bodyHadContent=false) — keeps a caller supplying just
    /// actors+messages+textColor+nodeColor unchanged.
    public Sequence(List<String> actors, List<SeqMessage> messages, String textColor, String nodeColor) {
        this(actors, messages, textColor, nodeColor, false);
    }

    /// No header node colour — keeps a caller that supplies just actors+messages+textColor unchanged.
    public Sequence(List<String> actors, List<SeqMessage> messages, String textColor) {
        this(actors, messages, textColor, null, false);
    }

    /// Default construction (`currentColor` message labels, default head fill) — keeps a caller that
    /// builds a Sequence from just its actors+messages unchanged.
    public Sequence(List<String> actors, List<SeqMessage> messages) {
        this(actors, messages, "currentColor", null, false);
    }
}
