package com.sirentide.ir;

import java.util.List;

/// A tensor-network diagram in Penrose graphical notation — the notation ubiquitous in
/// quantum-information / DMRG / tensor-network-ML papers (today hand-drawn in TikZ). This first
/// slice models a FIXED horizontal MPS chain: an ordered row of tensor CORES, each labelled, with a
/// BOND (virtual/contracted index) implicitly between every adjacent pair and one DANGLING PHYSICAL
/// index (leg) per core. `operator` marks an MPO (matrix-product OPERATOR): each core then carries a
/// SECOND vertical leg (one physical index up, one down) instead of the single MPS down-leg.
///
/// The geometry (core spacing, bond edges, leg direction/length) is DERIVED at layout time from this
/// thin IR ({@link com.sirentide.layout.TensorNetworkLayout}) — the parser is a plain tokenizer, so
/// the IR carries only the core labels + the MPS/MPO kind. `cores` is a defensive immutable copy;
/// an empty chain never reaches here (the parser degrades it to {@link Empty}). `textColor` reserves
/// the DSL `color=` seam other types use (unused by the current all-on-core-fill labels), defaulting
/// to `currentColor` so any future off-core label inherits the host page's text colour.
public record TensorNetwork(List<String> cores, boolean operator, String textColor)
    implements Diagram {

    public TensorNetwork {
        cores = List.copyOf(cores);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }
}
