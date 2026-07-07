package com.sirentide.ir;

import java.util.List;

/// One labeled FRAME block around a contiguous run of {@link SeqMessage}s — a mermaid-style `alt`
/// (alternatives split by `else`), `loop`, or `par` (parallel branches split by `and`). Blocks make
/// a sequence a real sequence diagram (docs/DESIGN.md §M2, the alt/loop/par milestone).
///
/// The message list stays FLAT (parse order, top→down); a block references the run it wraps by the
/// half-open-then-inclusive index range `[fromMsg, toMsg]` into that flat list. `fromMsg` is the
/// index of the first message inside the block; `toMsg` is the index of the LAST — so a block with a
/// single message has `fromMsg == toMsg`, and an EMPTY block (no messages between the keyword and its
/// `end`) has `toMsg < fromMsg` (layout skips its degenerate frame — inert, never throws).
///
/// `kind` is `alt` | `loop` | `par`. `label` is the free text after the keyword (`alt is available`
/// → `is available`; may be empty). `dividers` are the `else`/`and` split points (empty for `loop`,
/// and for an `alt`/`par` with no branch keyword). `depth` is the NESTING depth at open time (0 =
/// outermost) — layout insets a nested frame by `depth · INSET` so a block-in-a-block stays visibly
/// inside its parent.
public record SeqBlock(String kind, String label, int fromMsg, int toMsg,
                       List<Divider> dividers, int depth) {

    public SeqBlock {
        dividers = List.copyOf(dividers);
    }
}
