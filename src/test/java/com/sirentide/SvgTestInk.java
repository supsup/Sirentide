package com.sirentide;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Strict adapter for these own emitted node fixtures, not an arbitrary foreign-SVG validator.
 * Real LatteX integration remains a separate gate: unsupported geometry/transform never disappears
 * from the denominator. The controlled math fixtures use one closed path, no foreign transforms. */
final class SvgTestInk {
    record NodeInk(String primaryTag, String outline, List<DiamondInkOracle.Ink> ink) {}
    private record Transform(double sx, double sy, double tx, double ty) {
        static final Transform ID = new Transform(1,1,0,0);
        Transform then(Transform local) {
            return new Transform(sx*local.sx,sy*local.sy,sx*local.tx+tx,sy*local.ty+ty);
        }
    }
    private static final Pattern TRANSFORM = Pattern.compile("(translate|scale)\\s*\\(([^)]*)\\)");

    static List<NodeInk> nodes(String svg) {
        try {
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            Element root=f.newDocumentBuilder().parse(new InputSource(new StringReader(svg)))
                .getDocumentElement();
            if(!tag(root).equals("svg")) throw new AssertionError("expected SVG output");
            List<NodeInk> result=new ArrayList<>();
            var groups=root.getElementsByTagNameNS("*","g");
            for(int i=0;i<groups.getLength();i++) {
                Element g=(Element)groups.item(i);
                if(!g.getAttribute("data-sirentide-role").equals("node")) continue;
                if(g.hasAttribute("transform"))
                    throw new AssertionError("semantic node group must not transform geometry");
                List<Element> children=children(g);
                if(children.isEmpty()) throw new AssertionError("empty semantic node group");
                Element primary=children.get(0);
                if(!tag(primary).equals("path") && !tag(primary).equals("rect"))
                    throw new AssertionError("missing primary node silhouette");
                if(primary.hasAttribute("transform"))
                    throw new AssertionError("unexpected transformed node silhouette");
                List<DiamondInkOracle.Ink> ink=new ArrayList<>();
                for(int j=1;j<children.size();j++) collect(children.get(j),Transform.ID,ink);
                result.add(new NodeInk(tag(primary),tag(primary).equals("path")
                    ? required(primary,"d") : null,List.copyOf(ink)));
            }
            return List.copyOf(result);
        } catch(AssertionError e) {
            throw e;
        } catch(Exception e) {
            throw new AssertionError("SVG test setup/adapter failure (not containment evidence)",e);
        }
    }
    private static void collect(Element e,Transform inherited,List<DiamondInkOracle.Ink> into) {
        Transform t=inherited.then(transform(e.getAttribute("transform")));
        if(!Double.isFinite(t.sx) || !Double.isFinite(t.sy) || !Double.isFinite(t.tx)
                || !Double.isFinite(t.ty) || t.sx==0 || t.sy==0)
            throw new AssertionError("invalid/collapsed ink transform");
        switch(tag(e)) {
            case "g" -> { for(Element child:children(e)) collect(child,t,into); }
            case "path" -> {
                String d=required(e,"d");
                if(d.isBlank()) throw new AssertionError("empty emitted label path");
                into.add(new DiamondInkOracle.Ink(d,t.sx,t.sy,t.tx,t.ty));
            }
            default -> throw new AssertionError("unsupported test ink element: "+tag(e));
        }
    }
    private static String required(Element e,String name) {
        if(!e.hasAttribute(name)) throw new AssertionError("missing "+name);
        return e.getAttribute(name);
    }
    private static Transform transform(String raw) {
        if(raw.isBlank()) return Transform.ID;
        Matcher m=TRANSFORM.matcher(raw); int end=0; Transform t=Transform.ID;
        while(m.find()) {
            separator(raw.substring(end,m.start())); end=m.end();
            String args=m.group(2).strip();
            if(args.isEmpty()) throw new AssertionError("empty transform");
            String[] a=args.split("[\\s,]+");
            if(a.length<1 || a.length>2) throw new AssertionError("unsupported transform arity");
            double x=number(a[0]);
            double y=a.length==2?number(a[1]):m.group(1).equals("scale")?x:0;
            Transform local=m.group(1).equals("scale")
                ?new Transform(x,y,0,0):new Transform(1,1,x,y);
            t=t.then(local);
        }
        separator(raw.substring(end)); return t;
    }
    private static double number(String s) {
        double v=Double.parseDouble(s);
        if(!Double.isFinite(v)) throw new AssertionError("nonfinite transform");
        return v;
    }
    private static void separator(String s) {
        if(!s.chars().allMatch(c -> Character.isWhitespace(c) || c==','))
            throw new AssertionError("unsupported transform syntax");
    }
    private static String tag(Element e) {
        return e.getLocalName()==null?e.getTagName():e.getLocalName();
    }
    private static List<Element> children(Element e) {
        List<Element> result=new ArrayList<>();
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling()) {
            if(n instanceof Element child) result.add(child);
            else if(n.getNodeType()==Node.TEXT_NODE && !n.getTextContent().isBlank())
                throw new AssertionError("unexpected text inside emitted geometry");
        }
        return result;
    }
}
