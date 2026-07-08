package com.sirentide.ir;

import java.util.List;

/// One flowchart SUBGRAPH cluster — a titled bounding box around a run of member nodes, opened by
/// `subgraph <id> [title]` and closed by `end` in the DSL (docs/DESIGN.md §5, the cluster milestone).
///
/// `id` is the cluster's stable identity from the `subgraph <id>` token (never an edge endpoint in
/// v1 — clusters group nodes, they are not themselves node references); `title` is the display label
/// shown in the frame's top band (defaults to the id when no explicit `[title]`/`"title"` was given).
/// `memberNodeIds` are the node ids that were FIRST SEEN inside this cluster (or inside any of its
/// nested descendants — membership is TRANSITIVE up the open-cluster stack, so an outer cluster's box
/// encloses every inner cluster's members and the inner frame insets cleanly inside it). A node first
/// seen OUTSIDE never joins a cluster even if later referenced inside it (first-seen wins).
///
/// `depth` is the NESTING depth at open time (0 = outermost) — layout tightens a nested frame's
/// padding by `depth · inset` so a subgraph-in-a-subgraph sits visibly inside its parent. An EMPTY
/// cluster (no members — nothing was first-declared inside) draws no frame (degenerate → inert,
/// DESIGN §6). Additive: a flowchart with NO subgraphs carries an empty cluster list and bakes
/// byte-identically to before this milestone.
public record FlowCluster(String id, String title, List<String> memberNodeIds, int depth) {

    public FlowCluster {
        memberNodeIds = List.copyOf(memberNodeIds);
    }
}
