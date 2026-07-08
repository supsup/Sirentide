package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Mindmap;
import com.sirentide.ir.MindmapNode;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Sirentide's fourteenth diagram type: a `mindmap` parses INDENTATION into a hierarchy tree (root +
/// recursive children), and NEVER throws on malformed input (an over-indented first child, a line
/// shallower than the root, inconsistent indentation, an empty body, a single root). The tree GEOMETRY
/// (depth→x, parent-centering, edges) is proven separately in
/// {@link com.sirentide.layout.MindmapLayoutTest}.
class MindmapTest {

    private static Mindmap parse(String dsl) {
        Diagram d = DslParser.parse(dsl);
        return assertInstanceOf(Mindmap.class, d);
    }

    private static List<String> childTexts(MindmapNode node) {
        return node.children().stream().map(MindmapNode::text).toList();
    }

    @Test
    void nestedIndentationBuildsTheCorrectParentChildTree() {
        Mindmap m = parse("mindmap\n  root Root idea\n    Origins\n      Long history\n"
            + "      Popular\n    Tools\n      Mermaid\n");
        MindmapNode root = m.root();
        assertEquals("Root idea", root.text(), "the `root` keyword is stripped off the root text");
        assertEquals(List.of("Origins", "Tools"), childTexts(root), "root's two branches, in order");
        MindmapNode origins = root.children().get(0);
        assertEquals(List.of("Long history", "Popular"), childTexts(origins),
            "Origins' two leaf children, in declaration order");
        MindmapNode tools = root.children().get(1);
        assertEquals(List.of("Mermaid"), childTexts(tools), "Tools' single child");
        assertTrue(origins.children().get(0).children().isEmpty(), "a leaf has no children");
    }

    @Test
    void siblingOrderingFollowsDeclarationOrder() {
        MindmapNode root = parse("mindmap\n  Root\n    A\n    B\n    C\n    D\n").root();
        assertEquals(List.of("A", "B", "C", "D"), childTexts(root), "siblings keep declaration order");
    }

    @Test
    void aSingleRootOnlyMindmapIsJustTheRoot() {
        Mindmap m = parse("mindmap\n  Solo root\n");
        assertEquals("Solo root", m.root().text());
        assertTrue(m.root().children().isEmpty(), "a lone root has no children");
    }

    @Test
    void anEmptyBodyRoundTripsAsAMindmapWithNullRoot() {
        Mindmap m = parse("mindmap\n");
        assertNull(m.root(), "no body line → an empty tree (null root)");
        String svg = Sirentide.render("mindmap\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "a real inert canvas, not the 0x0 shell: " + svg);
    }

    @Test
    void anOverIndentedFirstChildAttachesToTheRoot() {
        // The first child is indented MUCH deeper than a normal child — with no intermediate parent it
        // still attaches to the root (the stack never pops below the root).
        MindmapNode root = parse("mindmap\n  Root\n          Deep child\n    Normal child\n").root();
        assertEquals(List.of("Deep child", "Normal child"), childTexts(root),
            "both attach to the root: " + childTexts(root));
    }

    @Test
    void aLineAtOrShallowerThanTheRootAttachesToTheRoot() {
        // A SECOND top-level line (same indent as the root) is not a second root — mermaid allows one
        // root, so it folds in as a root child.
        MindmapNode root = parse("mindmap\n  Root\n    A\n  Second top level\n").root();
        assertEquals(List.of("A", "Second top level"), childTexts(root),
            "the second top-level line becomes a root child: " + childTexts(root));
    }

    @Test
    void inconsistentIndentationSnapsToTheNearestShallowerAncestor() {
        // `Grandchild` is indented at an ODD depth (7 spaces) between its would-be parent (6) and
        // uncle levels — it snaps under the nearest strictly-shallower open node (Child at 4? no —
        // Sub at 6). Assert it lands under Sub, not Child.
        MindmapNode root = parse("mindmap\n  Root\n    Child\n      Sub\n       Grandchild\n").root();
        MindmapNode child = root.children().get(0);
        MindmapNode sub = child.children().get(0);
        assertEquals("Sub", sub.text());
        assertEquals(List.of("Grandchild"), childTexts(sub),
            "the odd-indent line snaps under the nearest shallower ancestor (Sub): " + childTexts(sub));
    }

    @Test
    void aBareRootKeywordYieldsAnEmptyTextRootNotAThrow() {
        MindmapNode root = parse("mindmap\n  root\n    Child\n").root();
        assertEquals("", root.text(), "a bare `root` keyword → an empty-text root node");
        assertEquals(List.of("Child"), childTexts(root));
    }

    @Test
    void malformedMindmapNeverThrowsAndBakesReal() {
        String dsl = "mindmap\n  root Root\n\t\tTab child\n    Origins\n         weird\n  shallow\n";
        String svg = Sirentide.render(dsl);
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "a real render, not the inert shell: " + svg);
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "mindmap\n  root Root idea\n    Origins\n      Long history\n    Tools\n      Mermaid\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void a11yDescReflectsTheTree() {
        String svg = Sirentide.render(
            "mindmap\n  root Root idea\n    Origins\n      Long history\n    Tools\n      Mermaid\n");
        int t = svg.indexOf("<desc>");
        int e = svg.indexOf("</desc>");
        assertTrue(t >= 0 && e > t, "a <desc> is baked: " + svg);
        String desc = svg.substring(t + 6, e);
        assertFalse(desc.isBlank(), "the desc is non-empty");
        assertTrue(desc.contains("Mindmap") && desc.contains("Root idea"),
            "the desc names the mindmap + root: " + desc);
        assertTrue(desc.contains("has children") && desc.contains("Origins") && desc.contains("Tools"),
            "the desc reads the tree (root's children): " + desc);
        assertTrue(desc.contains("Long history") && desc.contains("Mermaid"),
            "the desc reaches the leaf children: " + desc);
    }
}
