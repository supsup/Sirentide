package com.sirentide.ir;

import java.util.List;

/// One sequence-diagram NOTE — a bordered box of author text pinned to the current time position
/// (docs/DESIGN.md §M2, the note enrichment). A note is NOT a message: it does not connect two
/// actors, it annotates them. `position` is `over` | `left` | `right`:
///   • `over` centres the box on ONE actor's lifeline, or (with two actors) SPANS from the first to
///     the second (mermaid `note over A,B`).
///   • `left` / `right` place the box to the side of a SINGLE actor's lifeline.
///
/// `actors` are the referenced actor ids (already validated first-seen by the parser: 1 for
/// left/right, 1-or-2 for over). `text` is the note body (never null/blank — a textless note is
/// dropped at parse). `atMsg` anchors the note in EMIT ORDER: it is the index of the message the
/// note precedes (the count of messages parsed before this note line), so layout injects the note's
/// vertical band between the surrounding messages — exactly the {@code fromMsg}/{@code atMsg} index
/// convention {@link SeqBlock}/{@link Divider} use. Additive: a note-free sequence carries an EMPTY
/// note list and lays out/bakes BYTE-IDENTICALLY to the pre-note path.
public record SeqNote(String position, List<String> actors, String text, int atMsg) {

    public SeqNote {
        actors = List.copyOf(actors);
    }
}
