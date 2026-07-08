package com.sirentide.ir;

/// The parsed LEADING CONFIG BLOCK — a small, type-agnostic directive header the parser reads BEFORE
/// the diagram body, for ANY diagram type. Syntax (DESIGN: a simple, deterministic `%% key: value`
/// line set — cleaner than mermaid's `%%{init:{…}}%%`; an optional leading `sirentide` marker line is
/// accepted + ignored):
/// ```
/// sirentide
/// %% title: My Diagram
/// %% theme: dark
/// pie
///   "A" : 40
/// ```
/// Keys:
///   - `title`     — feeds the a11y `<title>` (overrides the derived accessible name). Capped to the
///                   parser's label cap.
///   - `theme`     — `default` | `dark` | `neutral` ({@link Theme}); an unknown value → `default`.
///   - `direction` — `TD` | `LR` (a forward-looking generalization; carried here for the types that
///                   will honour it — currently only surfaced, not yet re-applied over a type's own
///                   header direction). An unknown value → `null`.
///
/// UNKNOWN keys/values are IGNORED (inert) and a malformed directive NEVER throws (DESIGN §6). No
/// config at all yields {@link #DEFAULT} — which threads a byte-identical bake (no title override,
/// {@link Theme#DEFAULT}).
public record DiagramConfig(String title, Theme theme, String direction) {

    /// The no-config default: no title override, the byte-identical {@link Theme#DEFAULT}, no direction.
    public static final DiagramConfig DEFAULT = new DiagramConfig(null, Theme.DEFAULT, null);

    public DiagramConfig {
        // A null theme is normalized to DEFAULT so consumers never null-check (the parser always
        // passes a concrete theme, but the record stays robust to a direct construction).
        if (theme == null) {
            theme = Theme.DEFAULT;
        }
    }
}
