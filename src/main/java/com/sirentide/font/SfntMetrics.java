package com.sirentide.font;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// A minimal, clean-room reader for the *metrics* of an sfnt (TrueType/`glyf`) font — written
/// from the public OpenType/sfnt spec. It parses ONLY what label layout needs: `head`
/// (unitsPerEm), `hhea` (vertical metrics + numberOfHMetrics), `maxp` (numGlyphs), `hmtx`
/// (advance widths), and `cmap` (formats 4 and 12) for code-point → glyph mapping.
///
/// It deliberately does NOT read `loca`/`glyf` (glyph outlines) — those are only needed to
/// render text as `<path>`s, which lands at M1. For M0 the oracle sizes label boxes from
/// advance widths alone. All multi-byte sfnt integers are big-endian.
///
/// This is a metrics-focused re-implementation (not a port of LatteX's outline-heavy reader),
/// but the sfnt table layout is spec-fixed, so the byte offsets necessarily match the spec.
public final class SfntMetrics {

    /// Bundled label font — STIX Two Math (OFL), reused from LatteX so labels and
    /// LatteX-rendered formula fragments share one face. See resources/.../font/NOTICE.
    private static final String RESOURCE = "STIXTwoMath-Regular.ttf";

    private final byte[] data;
    private final Map<String, Integer> tableOffset = new HashMap<>();

    private final int unitsPerEm;
    private final int numGlyphs;
    private final int numberOfHMetrics;
    private final int ascender;   // font design units, y-up
    private final int descender;  // typically negative
    private final int lineGap;

    private final int cmapSubtableOffset;
    private final int cmapFormat;

    private final int[] loca;   // numGlyphs + 1 byte offsets into the glyf table

    private SfntMetrics(byte[] data) {
        this.data = data;

        long sfntVersion = u32(0);
        if (sfntVersion != 0x00010000L) {
            // 'OTTO' would be the CFF/OTF variant, which stores no glyf metrics we read here.
            throw new IllegalArgumentException(
                "Not a TrueType/glyf sfnt (sfntVersion=0x%08X)".formatted(sfntVersion));
        }
        int numTables = u16(4);
        int p = 12;
        for (int i = 0; i < numTables; i++) {
            String tag = new String(data, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
            tableOffset.put(tag, (int) u32(p + 8));
            p += 16;
        }
        require("head", "maxp", "hhea", "hmtx", "cmap", "loca", "glyf");

        int head = tableOffset.get("head");
        this.unitsPerEm = u16(head + 18);

        int hhea = tableOffset.get("hhea");
        this.ascender = s16(hhea + 4);
        this.descender = s16(hhea + 6);
        this.lineGap = s16(hhea + 8);
        this.numberOfHMetrics = u16(hhea + 34);

        this.numGlyphs = u16(tableOffset.get("maxp") + 4);

        this.loca = readLoca(s16(head + 50));  // head.indexToLocFormat

        int[] chosen = selectCmap();
        this.cmapSubtableOffset = chosen[0];
        this.cmapFormat = chosen[1];
    }

    /// Loads the bundled label font from the classpath.
    public static SfntMetrics loadBundled() {
        try (InputStream in = SfntMetrics.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled font resource not found: " + RESOURCE);
            }
            return new SfntMetrics(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled font", e);
        }
    }

    /// Design units per em — the scale all font-unit metrics are expressed in.
    public int unitsPerEm() {
        return unitsPerEm;
    }

    public int numGlyphs() {
        return numGlyphs;
    }

    /// The default line height in design units (ascender − descender + lineGap).
    public int lineHeightUnits() {
        return ascender - descender + lineGap;
    }

    /// Maps a Unicode code point to a glyph id, or 0 (.notdef) if unmapped.
    public int glyphId(int codePoint) {
        return switch (cmapFormat) {
            case 4 -> cmap4(codePoint);
            case 12 -> cmap12(codePoint);
            default -> throw new IllegalStateException("No usable cmap subtable");
        };
    }

    /// Horizontal advance of a glyph, in design units. Glyphs beyond `numberOfHMetrics`
    /// share the last entry's advance (the sfnt `hmtx` run-length convention).
    public int advanceWidth(int glyphId) {
        int hmtx = tableOffset.get("hmtx");
        int idx = Math.min(glyphId, numberOfHMetrics - 1);
        return u16(hmtx + idx * 4);
    }

    // -- glyph outlines (glyf/loca) ---------------------------------------------

    /// The contours of a glyph's outline, in font design units (y-up). Handles both SIMPLE glyphs
    /// and COMPOSITE glyphs (numberOfContours &lt; 0, e.g. an accented letter = base + accent
    /// component, each with an affine transform). Whitespace / empty glyphs return empty.
    public List<Contour> glyphContours(int glyphId) {
        return glyphContours(glyphId, 0);
    }

    private static final int MAX_COMPOSITE_DEPTH = 8;

    private List<Contour> glyphContours(int glyphId, int depth) {
        if (glyphId < 0 || glyphId + 1 >= loca.length) {
            return List.of();
        }
        int start = loca[glyphId];
        int end = loca[glyphId + 1];
        if (end <= start) {
            return List.of();   // no outline data (whitespace)
        }
        int g = tableOffset.get("glyf") + start;
        int numberOfContours = s16(g);
        return numberOfContours >= 0
            ? readSimpleContours(g, numberOfContours)
            : readCompositeContours(g, depth);
    }

    private int[] readLoca(int indexToLocFormat) {
        int locaOff = tableOffset.get("loca");
        int[] offsets = new int[numGlyphs + 1];
        if (indexToLocFormat == 0) {   // short: uint16 stored as (offset / 2)
            for (int i = 0; i <= numGlyphs; i++) {
                offsets[i] = u16(locaOff + i * 2) * 2;
            }
        } else {                       // long: uint32
            for (int i = 0; i <= numGlyphs; i++) {
                offsets[i] = (int) u32(locaOff + i * 4);
            }
        }
        return offsets;
    }

    /// Parses a simple (non-composite) glyf glyph: endPtsOfContours, the REPEAT_FLAG-encoded
    /// flags, and the delta-encoded x/y coordinates, split into per-contour point rings.
    private List<Contour> readSimpleContours(int g, int numberOfContours) {
        int pos = g + 10;
        int[] endPts = new int[numberOfContours];
        for (int i = 0; i < numberOfContours; i++) {
            endPts[i] = u16(pos);
            pos += 2;
        }
        int numPoints = numberOfContours == 0 ? 0 : endPts[numberOfContours - 1] + 1;

        int instructionLength = u16(pos);
        pos += 2 + instructionLength;

        int[] flags = new int[numPoints];
        for (int i = 0; i < numPoints; ) {
            int f = u8(pos++);
            flags[i++] = f;
            if ((f & 0x08) != 0) {           // REPEAT_FLAG
                int repeat = u8(pos++);
                while (repeat-- > 0 && i < numPoints) {
                    flags[i++] = f;
                }
            }
        }

        int[] xs = new int[numPoints];
        int x = 0;
        for (int i = 0; i < numPoints; i++) {
            int f = flags[i];
            if ((f & 0x02) != 0) {           // X_SHORT_VECTOR: 1 byte, sign from bit 0x10
                int dx = u8(pos++);
                x += (f & 0x10) != 0 ? dx : -dx;
            } else if ((f & 0x10) == 0) {     // signed 2-byte delta (else X_IS_SAME → 0)
                x += s16(pos);
                pos += 2;
            }
            xs[i] = x;
        }

        int[] ys = new int[numPoints];
        int y = 0;
        for (int i = 0; i < numPoints; i++) {
            int f = flags[i];
            if ((f & 0x04) != 0) {           // Y_SHORT_VECTOR
                int dy = u8(pos++);
                y += (f & 0x20) != 0 ? dy : -dy;
            } else if ((f & 0x20) == 0) {
                y += s16(pos);
                pos += 2;
            }
            ys[i] = y;
        }

        List<Contour> contours = new ArrayList<>(numberOfContours);
        int startPt = 0;
        for (int c = 0; c < numberOfContours; c++) {
            int endPt = endPts[c];
            List<GlyphPoint> pts = new ArrayList<>(endPt - startPt + 1);
            for (int i = startPt; i <= endPt; i++) {
                pts.add(new GlyphPoint(xs[i], ys[i], (flags[i] & 0x01) != 0));
            }
            contours.add(new Contour(pts));
            startPt = endPt + 1;
        }
        return contours;
    }

    // Composite-glyph component flags (OpenType glyf spec).
    private static final int ARG_1_AND_2_ARE_WORDS    = 0x0001;
    private static final int ARGS_ARE_XY_VALUES       = 0x0002;
    private static final int ROUND_XY_TO_GRID         = 0x0004;
    private static final int WE_HAVE_A_SCALE          = 0x0008;
    private static final int MORE_COMPONENTS          = 0x0020;
    private static final int WE_HAVE_AN_X_AND_Y_SCALE = 0x0040;
    private static final int WE_HAVE_A_TWO_BY_TWO     = 0x0080;
    private static final int SCALED_COMPONENT_OFFSET  = 0x0800;

    /// Reads a composite glyph: a chain of component records, each naming another glyph plus an
    /// affine transform (xy offset + optional scale / 2×2). Component outlines are fetched
    /// recursively and mapped into this glyph's space. Depth-guarded against cyclic glyphs
    /// (degrades to empty rather than throwing). Point-matched components (rare, non-XY) are
    /// placed without repositioning.
    private List<Contour> readCompositeContours(int g, int depth) {
        if (depth > MAX_COMPOSITE_DEPTH) {
            return List.of();
        }
        List<Contour> out = new ArrayList<>();
        int pos = g + 10;
        boolean more = true;
        while (more) {
            int flags = u16(pos);
            int componentGlyph = u16(pos + 2);
            pos += 4;

            int arg1;
            int arg2;
            if ((flags & ARG_1_AND_2_ARE_WORDS) != 0) {
                arg1 = s16(pos);
                arg2 = s16(pos + 2);
                pos += 4;
            } else {
                arg1 = (byte) u8(pos);
                arg2 = (byte) u8(pos + 1);
                pos += 2;
            }

            double a = 1;
            double b = 0;
            double c = 0;
            double d = 1;
            if ((flags & WE_HAVE_A_SCALE) != 0) {
                a = d = f2dot14(pos);
                pos += 2;
            } else if ((flags & WE_HAVE_AN_X_AND_Y_SCALE) != 0) {
                a = f2dot14(pos);
                d = f2dot14(pos + 2);
                pos += 4;
            } else if ((flags & WE_HAVE_A_TWO_BY_TWO) != 0) {
                a = f2dot14(pos);
                b = f2dot14(pos + 2);
                c = f2dot14(pos + 4);
                d = f2dot14(pos + 6);
                pos += 8;
            }

            double dx = 0;
            double dy = 0;
            if ((flags & ARGS_ARE_XY_VALUES) != 0) {
                dx = arg1;
                dy = arg2;
                if ((flags & SCALED_COMPONENT_OFFSET) != 0) {
                    double ox = dx;
                    double oy = dy;
                    dx = a * ox + c * oy;
                    dy = b * ox + d * oy;
                }
                if ((flags & ROUND_XY_TO_GRID) != 0) {
                    dx = Math.round(dx);
                    dy = Math.round(dy);
                }
            }

            for (Contour ct : glyphContours(componentGlyph, depth + 1)) {
                List<GlyphPoint> tp = new ArrayList<>(ct.points().size());
                for (GlyphPoint p : ct.points()) {
                    int nx = (int) Math.round(a * p.x() + c * p.y() + dx);
                    int ny = (int) Math.round(b * p.x() + d * p.y() + dy);
                    tp.add(new GlyphPoint(nx, ny, p.onCurve()));
                }
                out.add(new Contour(tp));
            }
            more = (flags & MORE_COMPONENTS) != 0;
        }
        return out;
    }

    /// Reads a 2.14 signed fixed-point number (F2Dot14): value = int16 / 16384.
    private double f2dot14(int p) {
        return s16(p) / 16384.0;
    }

    // -- cmap subtable selection + lookup (formats 4 and 12) --------------------

    private int[] selectCmap() {
        int cmap = tableOffset.get("cmap");
        int numTables = u16(cmap + 2);
        int best4 = -1;
        int best12 = -1;
        for (int i = 0; i < numTables; i++) {
            int rec = cmap + 4 + i * 8;
            int platformId = u16(rec);
            int encodingId = u16(rec + 2);
            int subOff = cmap + (int) u32(rec + 4);
            int format = u16(subOff);
            boolean unicode = platformId == 0
                || (platformId == 3 && (encodingId == 1 || encodingId == 10));
            if (!unicode) {
                continue;
            }
            if (format == 12 && best12 < 0) {
                best12 = subOff;
            } else if (format == 4 && best4 < 0) {
                best4 = subOff;
            }
        }
        if (best12 >= 0) {
            return new int[] {best12, 12};
        }
        if (best4 >= 0) {
            return new int[] {best4, 4};
        }
        throw new IllegalStateException("No Unicode cmap format 4 or 12 subtable found");
    }

    private int cmap4(int c) {
        if (c > 0xFFFF) {
            return 0;
        }
        int t = cmapSubtableOffset;
        int segCountX2 = u16(t + 6);
        int endCodes = t + 14;
        int startCodes = endCodes + segCountX2 + 2; // + reservedPad
        int idDeltas = startCodes + segCountX2;
        int idRangeOffsets = idDeltas + segCountX2;
        for (int i = 0; i < segCountX2 / 2; i++) {
            int end = u16(endCodes + i * 2);
            if (c > end) {
                continue;
            }
            int start = u16(startCodes + i * 2);
            if (c < start) {
                return 0;
            }
            int idDelta = s16(idDeltas + i * 2);
            int idRangeOffsetPos = idRangeOffsets + i * 2;
            int idRangeOffset = u16(idRangeOffsetPos);
            if (idRangeOffset == 0) {
                return (c + idDelta) & 0xFFFF;
            }
            int g = u16(idRangeOffsetPos + idRangeOffset + (c - start) * 2);
            return g == 0 ? 0 : (g + idDelta) & 0xFFFF;
        }
        return 0;
    }

    private int cmap12(int c) {
        int t = cmapSubtableOffset;
        int numGroups = (int) u32(t + 12);
        for (int i = 0; i < numGroups; i++) {
            int grp = t + 16 + i * 12;
            long startChar = u32(grp);
            long endChar = u32(grp + 4);
            if (c >= startChar && c <= endChar) {
                return (int) (u32(grp + 8) + (c - startChar));
            }
        }
        return 0;
    }

    private void require(String... tags) {
        for (String tag : tags) {
            if (!tableOffset.containsKey(tag)) {
                throw new IllegalArgumentException("Missing required sfnt table: " + tag);
            }
        }
    }

    // -- big-endian primitive reads ---------------------------------------------

    private int u8(int p) {
        return data[p] & 0xFF;
    }

    private int u16(int p) {
        return ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
    }

    private int s16(int p) {
        return (short) u16(p);
    }

    private long u32(int p) {
        return ((long) u16(p) << 16) | (u16(p + 2) & 0xFFFFL);
    }
}
