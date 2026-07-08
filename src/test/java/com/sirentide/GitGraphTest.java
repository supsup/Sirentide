package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.GitGraph;
import com.sirentide.ir.GitOp;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Sirentide's twelfth diagram type: a gitGraph parses its body into an ordered {@link GitOp} list —
/// `commit [id]`, `branch`, `checkout`, `merge` — and NEVER throws on malformed input (a commit before
/// any branch, an unknown-branch checkout/merge, a duplicate branch, a self-merge all parse to inert
/// ops or drop). The lane/colour/connector geometry is proven separately in
/// {@link com.sirentide.layout.GitGraphLayoutTest}.
class GitGraphTest {

    private static List<GitOp> ops(String dsl) {
        Diagram d = DslParser.parse(dsl);
        return assertInstanceOf(GitGraph.class, d).ops();
    }

    @Test
    void parsesTheFourOpKinds() {
        List<GitOp> ops = ops("gitGraph\n  commit\n  branch develop\n  checkout develop\n  merge main\n");
        assertEquals(4, ops.size());
        assertInstanceOf(GitOp.Commit.class, ops.get(0));
        assertEquals("develop", assertInstanceOf(GitOp.Branch.class, ops.get(1)).name());
        assertEquals("develop", assertInstanceOf(GitOp.Checkout.class, ops.get(2)).name());
        assertEquals("main", assertInstanceOf(GitOp.Merge.class, ops.get(3)).name());
    }

    @Test
    void commitParsesItsOptionalId() {
        List<GitOp> ops = ops("gitGraph\n  commit\n  commit id: \"fix\"\n  commit id:bare\n"
            + "  commit \"quoted\"\n");
        assertNull(assertInstanceOf(GitOp.Commit.class, ops.get(0)).id(), "a bare commit has no id");
        assertEquals("fix", assertInstanceOf(GitOp.Commit.class, ops.get(1)).id(), "id: \"quoted\"");
        assertEquals("bare", assertInstanceOf(GitOp.Commit.class, ops.get(2)).id(), "id:bare (no space)");
        assertEquals("quoted", assertInstanceOf(GitOp.Commit.class, ops.get(3)).id(), "commit \"x\"");
    }

    @Test
    void unknownKeywordLinesAreDroppedNotThrown() {
        // A leading token that is none of commit/branch/checkout/merge drops the line (inert).
        List<GitOp> ops = ops("gitGraph\n  commit\n  reset --hard\n  tag v1\n  branch dev\n");
        assertEquals(2, ops.size(), "only the commit + branch survive: " + ops);
        assertInstanceOf(GitOp.Commit.class, ops.get(0));
        assertInstanceOf(GitOp.Branch.class, ops.get(1));
    }

    @Test
    void emptyNamedDirectivesAreDropped() {
        // `branch`/`checkout`/`merge` with no name have nothing to name → dropped (inert), but a bare
        // `commit` is a legal unlabeled commit.
        List<GitOp> ops = ops("gitGraph\n  branch\n  checkout\n  merge\n  commit\n");
        assertEquals(1, ops.size(), "only the bare commit survives: " + ops);
        assertInstanceOf(GitOp.Commit.class, ops.get(0));
    }

    @Test
    void malformedSemanticsParseToInertOpsAndNeverThrow() {
        // A commit before any branch (implicit main), an unknown-branch checkout + merge, a duplicate
        // branch, and a self-merge — the parser tokenizes them; the LAYOUT resolves them inertly. The
        // point here is simply that NONE throw and the whole thing bakes to a real (non-inert) SVG.
        String dsl = "gitGraph\n  commit\n  checkout nope\n  merge nope\n  branch main\n"
            + "  branch dev\n  checkout dev\n  commit\n  checkout main\n  merge main\n  commit\n";
        List<GitOp> ops = ops(dsl);
        assertTrue(ops.size() >= 6, "the ops tokenize without loss of the valid ones: " + ops);
        String svg = Sirentide.render(dsl);
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertTrue(!svg.contains("width=\"0\" height=\"0\""), "a real render, not the inert shell: " + svg);
    }

    @Test
    void emptyBodyRoundTripsAsAGitGraphNotEmpty() {
        // A bare `gitGraph` is still a GitGraph (round-trips), NOT degraded to Empty.
        assertInstanceOf(GitGraph.class, DslParser.parse("gitGraph\n"));
        assertEquals(0, ops("gitGraph\n").size());
    }

    @Test
    void lowercaseAliasParses() {
        assertInstanceOf(GitGraph.class, DslParser.parse("gitgraph\n  commit\n"));
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "gitGraph\n  commit\n  branch dev\n  checkout dev\n  commit\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void a11yDescReflectsBranchesAndCommits() {
        String svg = Sirentide.render(
            "gitGraph\n  commit\n  commit id: \"fix\"\n  branch develop\n  checkout develop\n"
                + "  commit\n  checkout main\n  merge develop\n");
        int t = svg.indexOf("<desc>");
        int e = svg.indexOf("</desc>");
        assertTrue(t >= 0 && e > t, "a <desc> is baked: " + svg);
        String desc = svg.substring(t + 6, e);
        assertTrue(!desc.isBlank(), "the desc is non-empty");
        assertTrue(desc.contains("Git graph") && desc.contains("main") && desc.contains("develop"),
            "the desc names the branches: " + desc);
        assertTrue(desc.contains("fix"), "the desc names the labeled commit: " + desc);
        assertTrue(desc.contains("Merges") && desc.contains("develop into main"),
            "the desc reports the merge: " + desc);
    }
}
