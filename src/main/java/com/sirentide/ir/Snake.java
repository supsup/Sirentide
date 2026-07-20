package com.sirentide.ir;

import java.util.List;

/// A snake-graph continued-fraction figure (plan sirentide-snake-graph-primitive). A continued
/// fraction `[a_1, a_2, …, a_n]` — a list of POSITIVE-INTEGER partial quotients — renders as the
/// canonical Çanakçı–Schiffler SQUARE snake graph (Çanakçı & Schiffler, "Snake graphs and continued
/// fractions", arXiv 1608.06568): a connected strip of unit-square tiles whose turns encode the
/// quotients via the continued fraction's sign sequence.
///
/// TILE COUNT + TURNS (documented once, here, and mirrored by {@link
/// com.sirentide.layout.SnakeGraphLayout}): the snake has exactly `sum(a_i) − 1` tiles (NOT the sum).
/// The sign sequence is `sum(a_i)` signs, block `i` being `a_i` copies with the sign ALTERNATING
/// between blocks; a tile-junction is a TURN where its two bounding signs are EQUAL (inside one
/// block) and STRAIGHT where they DIFFER (a block boundary). So `[1, 1, …, 1]` (n ones) is a STRAIGHT
/// snake of `n − 1` tiles, and `[1]` is a single edge with ZERO tiles. This is the model whose
/// PERFECT-MATCHING count equals the NUMERATOR of the continued fraction (the semantic oracle proved
/// in {@code SnakeGraphLayoutTest}). NB: Propp's related construction (arXiv 2607.14332) uses HEXAGON
/// tiles, not squares — the square snake is Çanakçı–Schiffler's; do not attribute it to Propp.
///
/// `quotients` is the parsed, cap-bounded partial-quotient list (each positive; parse-side clamped
/// and the running quotient-sum bounded so a huge CF can't OOM — see {@code DslParser.parseSnake};
/// the tile count `sum − 1` is bounded by that sum). An empty list (a bare `snake` with no `cf:`) is
/// valid and bakes an empty canvas. `textColor` is unused by the current layout (the segment labels
/// sit ON the tile tints and take a contrast-derived fill) but is threaded for parity with the other
/// types and future off-strip labels; it defaults to `currentColor`.
public record Snake(List<Integer> quotients, String textColor) implements Diagram {

    public Snake {
        quotients = List.copyOf(quotients);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
