package com.sirentide.ir;

import java.util.Locale;

/// A Dynkin diagram (plan 8e13b196): the canonical graphical encoding of a semisimple Lie algebra's
/// root system — one of the most recognizable objects in mathematics (the ADE classification). NODES
/// are simple roots; the number of BONDS between two nodes encodes the angle θ via `4·cos²θ ∈ {1,2,3}`
/// (0 = orthogonal, not drawn), and a 2- or 3-bond carries an ARROW pointing FROM the longer root TO
/// the shorter root.
///
/// This record carries only the AUTHORED TYPE — a finite-type `family` letter (`A`–`G`) plus a `rank`.
/// The canonical node/bond/arrow structure is DERIVED deterministically from `(family, rank)` in
/// {@link com.sirentide.layout.DynkinDiagramLayout} (geometry) and paraphrased in {@link
/// com.sirentide.a11y.A11yDescriber} (words) — the field-set stays a tiny value, and the two consumers
/// agree because they share the same canonical numbering (documented on the layout).
///
/// FINITE TYPES (the built-in classification, with rank bounds — {@link #valid()}):
///   * A_n (n ≥ 1): n nodes in a line, all single bonds.
///   * B_n (n ≥ 2): a line whose LAST bond is double, arrow toward the last (short) node.
///   * C_n (n ≥ 2): a line whose last bond is double, arrow AWAY from the last (long) node — B_n and
///     C_n differ ONLY in that arrow direction.
///   * D_n (n ≥ 4): a line of n−2 nodes then a FORK — the (n−2)th node joins two terminal nodes.
///   * E_6/E_7/E_8: a line with one extra node branching off the 3rd node from one end.
///   * F_4: 4 nodes in a line, the MIDDLE bond double with an arrow.
///   * G_2: 2 nodes joined by a TRIPLE bond with an arrow.
///
/// An unknown letter or an out-of-range rank is {@link #valid() invalid} → it bakes the inert (empty,
/// MARGIN-padded) shell rather than throwing, exactly like every other Sirentide type degrades on
/// malformed input (DESIGN §6). `textColor` is threaded for parity with the other types (the Dynkin
/// diagram is LABEL-FREE in this slice, so nothing consumes it yet) and defaults to `currentColor`.
public record Dynkin(char family, int rank, String textColor) implements Diagram {

    public Dynkin {
        family = Character.toUpperCase(family);
        if (textColor == null) {
            textColor = "currentColor";
        }
    }

    /// True iff `(family, rank)` names a real finite-type Dynkin diagram within the supported rank
    /// bounds. An invalid type degrades to the inert shell (never throws).
    public boolean valid() {
        return switch (family) {
            case 'A' -> rank >= 1;
            case 'B', 'C' -> rank >= 2;
            case 'D' -> rank >= 4;
            case 'E' -> rank >= 6 && rank <= 8;
            case 'F' -> rank == 4;
            case 'G' -> rank == 2;
            default -> false;
        };
    }

    /// The number of nodes actually drawn — the rank for a valid type, else 0 (the inert shell).
    public int nodeCount() {
        return valid() ? rank : 0;
    }

    /// The canonical `family + rank` label (e.g. `B4`), used in the accessible description.
    public String typeLabel() {
        return String.valueOf(family).toUpperCase(Locale.ROOT) + rank;
    }
}
