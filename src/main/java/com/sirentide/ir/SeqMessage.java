package com.sirentide.ir;

/// One sequence-diagram message: `from ARROW to : label`. `from`/`to` are actor ids (both
/// auto-registered first-seen by the parser); `label` is OPTIONAL (`null` = an unlabeled message).
/// `reply` distinguishes a return/reply (`-->>`, drawn lighter with an OPEN-V arrowhead) from a
/// call (`->>`, a solid stroke with a FILLED triangle). A self-message has `from.equals(to)`.
public record SeqMessage(String from, String to, String label, boolean reply) {

    /// Call-message construction (solid, filled arrowhead) — the common case.
    public SeqMessage(String from, String to, String label) {
        this(from, to, label, false);
    }
}
