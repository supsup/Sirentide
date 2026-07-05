package com.sirentide.emit;

import com.sirentide.layout.LaidOut;

/// Pure emit: laid-out coordinates → SVG string. Emits ONLY the sirentide-output-contract
/// alphabet (docs/DESIGN.md §4/§5 — the contract Confluence authors at M0; the emitter's
/// left-containment test will assert output ⊆ contract). M0: the root `<svg>` shell, which is
/// already contract-clean (no script/style/foreignObject/on*).
public interface SvgEmitter {

    String emit(LaidOut laid);

    static SvgEmitter m0Default() {
        return laid -> "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "
            + fmt(laid.width()) + " " + fmt(laid.height()) + "\"></svg>";
    }

    /// Deterministic, locale-independent number formatting — integer when whole. Byte-identical
    /// output is a contract (docs/DESIGN.md §6): no `String.format` (locale), no hash-ordering.
    private static String fmt(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v)
            ? Long.toString((long) v)
            : Double.toString(v);
    }
}
