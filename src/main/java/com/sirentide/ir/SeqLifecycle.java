package com.sirentide.ir;

/// One participant LIFECYCLE event on a sequence-diagram actor — a mid-diagram `create participant X`
/// (`create == true`) or `destroy X` (`create == false`) (docs/DESIGN.md §M2, the create/destroy
/// enrichment). A `create` makes the actor's HEAD + LIFELINE start at the create point instead of the
/// top of the diagram; a `destroy` ENDS the lifeline at the destroy point with an `X` mark (two short
/// crossed lines).
///
/// `actor` is the referenced actor id (a `create` REGISTERS the actor first-seen; a `destroy` names an
/// already-registered actor — an unknown `destroy` is dropped at parse). `atMsg` anchors the event in
/// EMIT ORDER: it is the index of the message the directive precedes (the count of messages parsed
/// before this line), the same index convention notes/blocks use. Additive: a sequence with no
/// lifecycle events carries an EMPTY list and lays out/bakes BYTE-IDENTICALLY to the pre-lifecycle
/// path (every lifeline runs head-bottom→diagram-bottom, as before).
public record SeqLifecycle(String actor, boolean create, int atMsg) {}
