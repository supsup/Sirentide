package com.sirentide;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DiamondInkOracle {
    record P(double x, double y) {}
    record Ink(String d, double sx, double sy, double tx, double ty) {
        Ink(String d) { this(d, 1, 1, 0, 0); }
    }
    private static final Pattern TOKEN = Pattern.compile(
        "[MLQZ]|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?");
    private static final class Tokens {
        final List<String> ts = new ArrayList<>(); int at;
        Tokens(String d) {
            Matcher m = TOKEN.matcher(d); int end = 0;
            while (m.find()) {
                gap(d.substring(end, m.start())); ts.add(m.group()); end = m.end();
            }
            gap(d.substring(end));
        }
        static void gap(String s) {
            if (!s.chars().allMatch(c -> Character.isWhitespace(c) || c == ','))
                throw new AssertionError("unsupported SVG path syntax");
        }
        String next() {
            if (at == ts.size()) throw new AssertionError("incomplete SVG path");
            return ts.get(at++);
        }
        double number() {
            double d;
            try { d = Double.parseDouble(next()); }
            catch (NumberFormatException e) { throw new AssertionError("expected number", e); }
            if (!Double.isFinite(d)) throw new AssertionError("nonfinite path coordinate");
            return d;
        }
        P point(double sx, double sy, double dx, double dy) {
            double x = number() * sx + dx, y = number() * sy + dy;
            if (!Double.isFinite(x) || !Double.isFinite(y))
                throw new AssertionError("nonfinite transformed coordinate");
            return new P(x, y);
        }
        boolean has() { return at != ts.size(); }
    }
    private static double cross(P a, P b, P c) {
        return (b.x-a.x)*(c.y-a.y) - (b.y-a.y)*(c.x-a.x);
    }
    static List<P> diamond(String d) {
        Tokens t = new Tokens(d); List<P> p = new ArrayList<>();
        if (!t.next().equals("M")) throw new AssertionError("diamond must begin M");
        p.add(t.point(1,1,0,0));
        for (int i=0; i<3; i++) {
            if (!t.next().equals("L")) throw new AssertionError("diamond must have four vertices");
            p.add(t.point(1,1,0,0));
        }
        if (!t.next().equals("Z") || t.has()) throw new AssertionError("diamond must close once");
        double sign = Math.signum(cross(p.get(0),p.get(1),p.get(2)));
        if (sign == 0) throw new AssertionError("degenerate diamond");
        for (int i=0; i<4; i++) {
            if (sign*cross(p.get(i),p.get((i+1)%4),p.get((i+2)%4)) <= 0)
                throw new AssertionError("nonconvex/degenerate silhouette");
        }
        // Read actual topology, never reconstruct it from label width/height policy.
        P top=p.get(0), right=p.get(1), bottom=p.get(2), left=p.get(3);
        double e=.001; // independent 3dp vertex rounding can offset a midpoint by .0005px
        if (!(top.y < right.y && right.y < bottom.y && left.x < top.x && top.x < right.x)
                || Math.abs(top.x-bottom.x)>e || Math.abs(left.y-right.y)>e
                || Math.abs((left.x+right.x)/2-top.x)>e
                || Math.abs((top.y+bottom.y)/2-right.y)>e)
            throw new AssertionError("expected actual axis-aligned diamond topology");
        return p;
    }
    static void assertInside(String actualOutlineD, List<Ink> actualInk, double epsilonPx) {
        if (!Double.isFinite(epsilonPx) || epsilonPx < 0 || epsilonPx > .01)
            throw new AssertionError("unbounded oracle tolerance");
        List<P> p = diamond(actualOutlineD);
        double orientation = Math.signum(cross(p.get(0),p.get(1),p.get(2)));
        if (actualInk.isEmpty()) throw new AssertionError("missing label ink");
        int segments = 0;
        for (Ink ink : actualInk) {
            if (!Double.isFinite(ink.sx) || !Double.isFinite(ink.sy)
                    || !Double.isFinite(ink.tx) || !Double.isFinite(ink.ty))
                throw new AssertionError("nonfinite emitted translation");
            Tokens t = new Tokens(ink.d); P current=null, start=null;
            while (t.has()) {
                String op = t.next();
                switch(op) {
                    case "M" -> current = start = t.point(ink.sx,ink.sy,ink.tx,ink.ty);
                    case "L" -> {
                        if (current==null) throw new AssertionError("L without M");
                        P end=t.point(ink.sx,ink.sy,ink.tx,ink.ty);
                        check(p,orientation,current,null,end,epsilonPx);current=end;segments++;
                    }
                    case "Q" -> {
                        if (current==null) throw new AssertionError("Q without M");
                        P control=t.point(ink.sx,ink.sy,ink.tx,ink.ty);
                        P end=t.point(ink.sx,ink.sy,ink.tx,ink.ty);
                        check(p,orientation,current,control,end,epsilonPx);current=end;segments++;
                    }
                    case "Z" -> {
                        if (current==null || start==null) throw new AssertionError("Z without M");
                        check(p,orientation,current,null,start,epsilonPx);current=start;segments++;
                    }
                    default -> throw new AssertionError("unsupported SVG path command: "+op);
                }
            }
        }
        if (segments==0) throw new AssertionError("no drawable label segments");
    }
    private static void check(List<P> polygon,double sign,P a,P b,P c,double eps) {
        for(int i=0;i<polygon.size();i++) {
            P u=polygon.get(i),v=polygon.get((i+1)%polygon.size());
            double len=Math.hypot(v.x-u.x,v.y-u.y);
            if (!Double.isFinite(len) || len <= 0) throw new AssertionError("invalid edge length");
            double f0=sign*cross(u,v,a)/len, f2=sign*cross(u,v,c)/len;
            if (!Double.isFinite(f0) || !Double.isFinite(f2))
                throw new AssertionError("nonfinite edge distance");
            double min=Math.min(f0,f2);
            if(b!=null) {
                double f1=sign*cross(u,v,b)/len;
                double denominator=f0-2*f1+f2;
                if (!Double.isFinite(f1) || !Double.isFinite(denominator))
                    throw new AssertionError("nonfinite quadratic distance");
                if(denominator!=0) {
                    double t=(f0-f1)/denominator;
                    if(t>0 && t<1) {
                        double q=(1-t)*(1-t)*f0+2*(1-t)*t*f1+t*t*f2;
                        min=Math.min(min,q);
                    }
                }
            }
            if(!Double.isFinite(min) || min < -eps)
                throw new AssertionError("emitted ink outside polygon edge "+i+": "+min);
        }
    }
    /** A different oracle: every actual glyph contour must match its recorded full text run.
     * Reuses the pinned font as glyph authority, never FlowchartLayout's sizing formula. */
    static void assertTranslatedPath(String expected, String actual, double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < 0 || tolerance > .011)
            throw new AssertionError("unbounded glyph identity tolerance");
        Tokens a=new Tokens(expected), b=new Tokens(actual);
        if (a.ts.isEmpty() || a.ts.size()!=b.ts.size())
            throw new AssertionError("missing/extra glyph path tokens");
        double dx=Double.NaN,dy=Double.NaN; int coordinate=0;
        for(int i=0;i<a.ts.size();i++) {
            String x=a.ts.get(i), y=b.ts.get(i);
            boolean command=x.length()==1 && "MLQZ".contains(x);
            if(command) {
                if(!x.equals(y)) throw new AssertionError("glyph command changed");
            } else {
                double ex,ac;
                try { ex=Double.parseDouble(x); ac=Double.parseDouble(y); }
                catch(NumberFormatException e) { throw new AssertionError("glyph coordinate changed",e); }
                double delta=ac-ex;
                if(!Double.isFinite(delta)) throw new AssertionError("nonfinite glyph coordinate");
                if(coordinate==0) dx=delta;
                if(coordinate==1) dy=delta;
                double origin=coordinate%2==0?dx:dy;
                if(Math.abs(delta-origin)>tolerance)
                    throw new AssertionError("glyph shape changed beyond rigid translation");
                coordinate++;
            }
        }
        if(coordinate<2 || coordinate%2!=0) throw new AssertionError("missing glyph coordinates");
    }

}
