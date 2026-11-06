package dev.malaiesun.home;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Pixel art for Pixie, the TV's mascot (a small visor robot), plus its coin.
 * The head and face (white head, dark visor, cyan eyes) are the same in every theme; the outfit changes:
 * body colour, scarf or cape, boots, antenna light, chest emblem and outline.
 * Art is authored as fills; render() adds a 1-pixel outline. Frames face right, 16 x 16.
 *
 * Codes: H/h head + shade, V visor, C eyes (fixed) - W/G body + shade, R/r scarf, B boots, Y antenna,
 * K antenna stem, S emblem, P/p cape (outfit).
 */
final class Sprites {

    static final String[] IDLE = {
            ".......YY.......",
            ".......K........",
            "....HHHHHHHH....",
            "...HHHHHHHHHH...",
            "...HHVVVVVVVV...",
            "...HHVVCVVVCV...",
            "...HHVVCVVVCV...",
            "...HHVVVVVVVV...",
            "...hHHHHHHHHh...",
            "....hhhhhhhh....",
            "..rRRRRRRRRR....",
            ".rr.GWWWWWWG....",
            "r...WWWWWWWWG...",
            "....WWGGGGWW....",
            ".....BB..BB.....",
            "....BBB..BBB....",
    };

    static final String[] BLINK = {
            ".......YY.......",
            ".......K........",
            "....HHHHHHHH....",
            "...HHHHHHHHHH...",
            "...HHVVVVVVVV...",
            "...HHVVVVVVVV...",
            "...HHVVCCVCCV...",
            "...HHVVVVVVVV...",
            "...hHHHHHHHHh...",
            "....hhhhhhhh....",
            "..rRRRRRRRRR....",
            ".rr.GWWWWWWG....",
            "r...WWWWWWWWG...",
            "....WWGGGGWW....",
            ".....BB..BB.....",
            "....BBB..BBB....",
    };

    static final String[] RUN1 = {
            ".......YY.......",
            ".......K........",
            "....HHHHHHHH....",
            "...HHHHHHHHHH...",
            "...HHVVVVVVVV...",
            "...HHVVCVVVCV...",
            "...HHVVCVVVCV...",
            "...HHVVVVVVVV...",
            "...hHHHHHHHHh...",
            "....hhhhhhhh....",
            "rrRRRRRRRRRR....",
            "....GWWWWWWGG...",
            "....WWWWWWWW....",
            "....WWGGGGWW....",
            "...BB....BB.....",
            "..BB......BBB...",
    };

    static final String[] RUN2 = {
            ".......YY.......",
            ".......K........",
            "....HHHHHHHH....",
            "...HHHHHHHHHH...",
            "...HHVVVVVVVV...",
            "...HHVVCVVVCV...",
            "...HHVVCVVVCV...",
            "...HHVVVVVVVV...",
            "...hHHHHHHHHh...",
            "....hhhhhhhh....",
            ".rRRRRRRRRRR....",
            "r...GWWWWWWG....",
            "....WWWWWWWWG...",
            "....WWGGGGWW....",
            "......BBB.......",
            ".....BBBB.......",
    };

    /** Fist up, legs tucked: the block-bump pose. */
    static final String[] JUMP = {
            ".......YY.....WW",
            ".......K......WW",
            "....HHHHHHHH..G.",
            "...HHHHHHHHHH.G.",
            "...HHVVVVVVVV.G.",
            "...HHVVCVVVCV.G.",
            "...HHVVCVVVCV.G.",
            "...HHVVVVVVVV.G.",
            "...hHHHHHHHHh.G.",
            "....hhhhhhhh.G..",
            "...rRRRRRRRRG...",
            "..r.GWWWWWWG....",
            ".r..WWWWWWWW....",
            "....WWGGGGWW....",
            "....BB..BBB.....",
            "...BB....BB.....",
    };

    /** Waving hello: hand up and out ... */
    static final String[] WAVE1 = {
            ".......YY.......",
            ".......K........",
            "....HHHHHHHH..WW",
            "...HHHHHHHHHH.WW",
            "...HHVVVVVVVV.G.",
            "...HHVVCVVVCV.G.",
            "...HHVVCVVVCV.G.",
            "...HHVVVVVVVV.G.",
            "...hHHHHHHHHh.G.",
            "....hhhhhhhh.G..",
            "..rRRRRRRRRRG...",
            ".rr.GWWWWWWG....",
            "r...WWWWWWWW....",
            "....WWGGGGWW....",
            ".....BB..BB.....",
            "....BBB..BBB....",
    };

    /** ... and tilted in. */
    static final String[] WAVE2 = {
            ".......YY.......",
            ".......K.....WW.",
            "....HHHHHHHH.WW.",
            "...HHHHHHHHHH.G.",
            "...HHVVVVVVVV.G.",
            "...HHVVCVVVCV.G.",
            "...HHVVCVVVCV.G.",
            "...HHVVVVVVVV.G.",
            "...hHHHHHHHHh.G.",
            "....hhhhhhhh.G..",
            "..rRRRRRRRRRG...",
            ".rr.GWWWWWWG....",
            "r...WWWWWWWW....",
            "....WWGGGGWW....",
            ".....BB..BB.....",
            "....BBB..BBB....",
    };

    // Cape layers, drawn behind the body for caped outfits (only rows 10-15 used).
    static final String[] CAPE_STAND = {
            "", "", "", "", "", "", "", "", "", "",
            "..PP............",
            ".PPPP...........",
            ".PPPP...........",
            "PPPPPp..........",
            "PPPPp...........",
            ".PPp............",
    };
    static final String[] CAPE_RUN = {
            "", "", "", "", "", "", "", "", "", "",
            "PPPP............",
            "PPPPP...........",
            "PPPPp...........",
            ".PPp............",
            "..p.............",
            "",
    };
    static final String[] CAPE_JUMP = {
            "", "", "", "", "", "", "", "", "", "",
            "...P............",
            "..PPP...........",
            "..PPP...........",
            "..PPPp..........",
            "..PPp...........",
            "...p............",
    };

    /** Jetpack strapped on the back (futuristic outfits), drawn over the scarf tail. Nozzle at art col 2, row 13. */
    static final String[] JETPACK = {
            "", "", "", "", "", "", "", "", "", "",
            "..JJ............",
            ".JJJ............",
            ".JJJ............",
            "..j.............",
    };
    static final int NOZZLE_X = 3, NOZZLE_Y = 15;   // bitmap pixels, just under the nozzle

    /** Chest emblem, drawn over the body. */
    static final String[] EMBLEM = {
            "", "", "", "", "", "", "", "", "", "", "",
            ".......SS.......",
            "......SSSS......",
    };

    static final String[] COIN = {
            ".YYYY.",
            "YwYYYY",
            "YYoYYY",
            "YYoYYY",
            "YYoYYY",
            "YYoYYY",
            "YYYYYY",
            ".YYYY.",
    };

    /** Antenna light position in bitmap pixels (art col 7-8, row 0, plus the 1-pixel outline margin). */
    static final int ANT_X = 8, ANT_Y = 1, ANT_W = 2;

    /** What Pixie wears in one theme. The face never changes. */
    static final class Outfit {
        int body, bodyShade, scarf, boots, antenna, emblem, outline = 0xFF101420;
        boolean cape, hasEmblem, jetpack;

        static Outfit of(String theme) {
            Outfit o = new Outfit();
            switch (theme == null ? "" : theme) {
                case "comic":       // yellow suit, red cape, blue bolt
                    o.body = 0xFFFFD60A; o.bodyShade = 0xFFE09A00; o.scarf = 0xFFE63946; o.boots = 0xFFE63946;
                    o.antenna = 0xFFE63946; o.emblem = 0xFF1D4ED8; o.outline = 0xFF111111; o.cape = true; o.hasEmblem = true;
                    break;
                case "hero":        // steel suit, red cape, gold star
                    o.body = 0xFF34466A; o.bodyShade = 0xFF1F2B45; o.scarf = 0xFFE23636; o.boots = 0xFFF2C14E;
                    o.antenna = 0xFFF2C14E; o.emblem = 0xFFF2C14E; o.outline = 0xFF05070D; o.cape = true; o.hasEmblem = true;
                    break;
                case "neon":        // dark violet suit, magenta scarf, glowing cyan outline
                    o.body = 0xFF2A1450; o.bodyShade = 0xFF180A33; o.scarf = 0xFFFF2BD6; o.boots = 0xFF00E5FF;
                    o.antenna = 0xFF00E5FF; o.outline = 0xFF00E5FF; o.jetpack = true;
                    break;
                case "aurora":      // lavender suit, violet scarf
                    o.body = 0xFFE6E0FF; o.bodyShade = 0xFFB9AEF0; o.scarf = 0xFFB266FF; o.boots = 0xFF5B8CFF;
                    o.antenna = 0xFFFFFFFF;
                    break;
                case "minimal":     // plain, soft blue scarf
                    o.body = 0xFFE9ECF2; o.bodyShade = 0xFFB8BEC9; o.scarf = 0xFF5B8CFF; o.boots = 0xFF4A5060;
                    o.antenna = 0xFF8AB4FF;
                    break;
                default:            // retro: the classic white suit, red scarf
                    o.body = 0xFFF4F6FB; o.bodyShade = 0xFFA9B3CC; o.scarf = 0xFFFF5A36; o.boots = 0xFF3B4466;
                    o.antenna = 0xFFFFD84D;
                    break;
            }
            return o;
        }

        int color(char c) {
            switch (c) {
                case 'H': return 0xFFF4F6FB;
                case 'h': return 0xFFA9B3CC;
                case 'V': return 0xFF1C2540;
                case 'C': return 0xFF5CF2FF;
                case 'W': return body;
                case 'G': return bodyShade;
                case 'R': case 'P': return scarf;
                case 'r': case 'p': return Theme.shade(scarf, -0.3f);
                case 'B': return boots;
                case 'Y': return antenna;
                case 'K': return outline;
                case 'S': return emblem;
                case 'J': return 0xFF8A97B8;
                case 'j': return 0xFF4A5470;
                case 'o': return 0xFFD08A10;
                case 'w': return 0xFFFFFFFF;
                default: return 0;
            }
        }
    }

    /** All frames for one outfit, as small bitmaps. */
    static final class Set {
        final Outfit outfit;
        final Bitmap idle, blink, run1, run2, jump, wave1, wave2, coin;

        Set(String theme) {
            Outfit o = outfit = Outfit.of(theme);
            String[] stand = o.cape ? CAPE_STAND : null, run = o.cape ? CAPE_RUN : null, up = o.cape ? CAPE_JUMP : null;
            String[] em = o.hasEmblem ? EMBLEM : o.jetpack ? JETPACK : null;
            idle = render(IDLE, o, stand, em);
            blink = render(BLINK, o, stand, em);
            run1 = render(RUN1, o, run, em);
            run2 = render(RUN2, o, run, em);
            jump = render(JUMP, o, up, em);
            wave1 = render(WAVE1, o, stand, em);
            wave2 = render(WAVE2, o, stand, em);
            Outfit gold = Outfit.of("retro");
            gold.antenna = 0xFFFFD84D;
            gold.outline = o.outline;
            coin = render(COIN, gold, null, null);
        }
    }

    private static final Map<String, Set> cache = new HashMap<>();

    static synchronized Set forTheme(String theme) {
        Set s = cache.get(theme);
        if (s == null) { s = new Set(theme); cache.put(theme, s); }
        return s;
    }

    /** Fills (underlay behind, base, overlay on top) plus an automatic outline (4-neighbourhood). */
    static Bitmap render(String[] art, Outfit o, String[] under, String[] over) {
        int h = art.length, w = art[0].length(), W = w + 2, H = h + 2;
        int[] px = new int[W * H];
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) {
                int col = o.color(art[r].charAt(c));
                if (col == 0 && under != null && r < under.length && c < under[r].length()) col = o.color(under[r].charAt(c));
                if (over != null && r < over.length && c < over[r].length() && over[r].charAt(c) != '.') col = o.color(over[r].charAt(c));
                px[(r + 1) * W + c + 1] = col;
            }
        int[] out = px.clone();
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                if (px[y * W + x] != 0) continue;
                boolean edge = (x > 0 && px[y * W + x - 1] != 0) || (x < W - 1 && px[y * W + x + 1] != 0)
                        || (y > 0 && px[(y - 1) * W + x] != 0) || (y < H - 1 && px[(y + 1) * W + x] != 0);
                if (edge) out[y * W + x] = o.outline;
            }
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, W, 0, 0, W, H);
        return b;
    }

    private Sprites() { }
}
