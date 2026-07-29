package com.sirentide.parse;

/// Thrown when a label carries tag-shaped markup that this renderer does not support.
///
/// ## Why an exception rather than a silent degrade
///
/// The defect this closes is that `A[TRUE NEGATIVE<br/>safe to act on]` rendered the
/// characters `<br/>` as visible text with exit 0, a well-formed SVG and no diagnostic —
/// every automated check passed and only a human looking at the picture could tell.
///
/// Throwing here reuses the machinery that already exists rather than adding a second
/// loudness mechanism: {@code Sirentide.render} catches it and degrades to the byte-stable
/// INERT SHELL, preserving the never-throw contract (DESIGN §6/§7), while
/// {@code renderWithDiagnostics} classifies it as {@code PARSE_ERROR} at stage `parse`. The
/// render-check CLI and the docs converter already treat a non-OK diagnostic as loud failure
/// and retain the source rather than publishing a wrong diagram — so the loudness arrives for
/// free once the diagnostic is correct.
///
/// ## The fields are the contract
///
/// Marlow's ruling (sirentide/667) requires a STABLE LABEL IDENTITY and a BOUNDED,
/// control-sanitized token echo. Both are carried structurally rather than baked into a
/// message string, so the classifier can compose the wording and a future caller can key on
/// the parts.
///
/// The token arrives already bounded and sanitized from {@link LabelMarkup#offendingTag} —
/// the diagnostic is itself an output surface that gets printed, logged and pasted, so a
/// hostile label must not be able to inject newlines or terminal escapes into it.
public final class LabelMarkupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /// Stable identity of the offending label: a node id where one exists, else the diagram
    /// type plus a stable row/item index. Never the label text itself — that is unbounded.
    private final String labelId;

    /// The offending token, already bounded and control-sanitized.
    private final String tag;

    public LabelMarkupException(String labelId, String tag) {
        super("unsupported markup " + tag + " in label " + labelId);
        this.labelId = labelId;
        this.tag = tag;
    }

    public String labelId() {
        return labelId;
    }

    public String tag() {
        return tag;
    }
}
