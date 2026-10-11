package com.jarvis.tracker;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** People as silhouettes against a learned background, every camera frame.
 *
 *  Why not the detector for this: its boxes wobble ~3% when you stand still,
 *  stretch toward a raised arm, often stop short of outstretched fingertips,
 *  and arrive ~250 ms late at ~7/s on this box. A silhouette gives exactly
 *  what the game needs at 30/s: where your body is, and how far your arms
 *  reach at shoulder height.
 *
 *  - background: per-cell running mean of luma, learned fast where nothing is
 *    in front of it and almost not at all where a confirmed person stands
 *  - exposure: the webcam auto-exposes and can't be locked (aeLockAvailable
 *    false), so each frame is gain-matched to the background first
 *  - people: column runs of foreground; arm cells are too thin to count as
 *    body columns, so the body centre ignores arms
 *  - arms out: foreground reach in the shoulder band vs. blob height
 *  - the detector only confirms that a blob is a person (pets, curtains and
 *    "ghosts" of moved things never are, and get learned into the background)
 */
final class Silhouette {

    static final int GW = 160, GH = 120;          // 2x2 cells of the 320x240 frame
    private static final float LR_BG = 0.03f;     // learning where the background shows (~1 s)
    private static final float LR_PERSON = 0.0003f; // under a confirmed person (~2 min)
    private static final float LR_UNCONFIRMED = 0.02f;
    private static final int THRESH = 22;         // luma difference that is foreground
    private static final float COL_MIN = 0.10f;   // fg fraction of a column to count as body
    // first run: a swinging arm / shadow beside a player became its own thin
    // blob, got "confirmed" by the player's detection box, and a second umbrella
    private static final int MIN_BODY_COLS = 8;   // 5% of the frame
    private static final float MIN_HEIGHT = 0.25f;
    private static final int GAP_COLS = 6;        // body columns closer than this merge
    // test: arms out 0.71-0.77, arms down 0.34-0.55, fast strides to ~0.63
    private static final float ARMS_OUT = 0.66f;  // shoulder reach / blob height
    private static final float ARMS_IN = 0.58f;
    private static final int ARM_FRAMES = 4;      // debounce, ~130 ms
    private static final float LIGHTING_JUMP = 0.45f;  // fg share that means "the lights changed"

    private final float[] bg = new float[GW * GH];
    private final int[] cur = new int[GW * GH];
    private final boolean[] fg = new boolean[GW * GH];
    private final int[] colCount = new int[GW];
    private final int[] owner = new int[GW];      // blob index per column, -1 none
    private int frames;
    private float fgShare;
    private long relearnUntil;

    private final List<Blob> blobs = new ArrayList<>();
    // confirmed people who just vanished (merged with someone, stepped behind
    // a chair): a blob reappearing near one takes back its id and umbrella
    private final List<Blob> lost = new ArrayList<>();
    private static final long LOST_KEEP_MS = 3000;
    // recent detector boxes {l, r, at}: silhouette pieces inside one box are
    // one person (a kid split by clothes matching the wall got two umbrellas)
    private final List<float[]> recentDets = new ArrayList<>();
    private static final long DET_MEMORY_MS = 1000;
    private int nextId = 1;
    private long lastLog;

    static final class Blob {
        final int id;
        int x0, x1, top, bottom;      // body columns, rows (grid units)
        float cx;                      // body centre, 0..1 of frame
        float reach, height;           // shoulder-band extent and blob height, grid units
        boolean armsOut, confirmed;
        int armStreak;
        long bornAt, seenAt;
        Blob(int id, long now) { this.id = id; bornAt = now; seenAt = now; }
        float reachRatio() { return height > 0 ? reach / height : 0; }
    }

    /** Feed one frame's Y plane. Returns the people (confirmed blobs). */
    synchronized List<TrackSink.Block> process(java.nio.ByteBuffer y, int rowStride, int pixStride) {
        long now = SystemClock.elapsedRealtime();
        // 2x2 luma average
        for (int gy = 0; gy < GH; gy++) {
            int r0 = (gy * 2) * rowStride, r1 = r0 + rowStride;
            for (int gx = 0; gx < GW; gx++) {
                int c0 = gx * 2 * pixStride, c1 = c0 + pixStride;
                cur[gy * GW + gx] = ((y.get(r0 + c0) & 0xff) + (y.get(r0 + c1) & 0xff)
                    + (y.get(r1 + c0) & 0xff) + (y.get(r1 + c1) & 0xff)) >> 2;
            }
        }
        if (frames++ == 0) {
            for (int i = 0; i < bg.length; i++) bg[i] = cur[i];
            return new ArrayList<>();
        }

        // exposure: match this frame's brightness to the background, using
        // the cells that were background last frame
        double sc = 0, sb = 0;
        for (int i = 0; i < bg.length; i++) if (!fg[i]) { sc += cur[i]; sb += bg[i]; }
        float gain = sc > 0 ? (float) (sb / sc) : 1f;
        gain = Math.max(0.6f, Math.min(1.6f, gain));

        int n = 0;
        for (int i = 0; i < bg.length; i++) {
            fg[i] = Math.abs(cur[i] * gain - bg[i]) > THRESH;
            if (fg[i]) n++;
        }
        fgShare = n / (float) bg.length;
        if (fgShare > LIGHTING_JUMP && now > relearnUntil) {
            relearnUntil = now + 1500;   // lights changed: relearn everything quickly
            Log.d(TrackerActivity.TAG, String.format("sil lighting change (fg %.0f%%), relearning", fgShare * 100));
        }

        findBlobs(now);
        learn(gain, now);
        logState(now, gain);

        List<TrackSink.Block> out = new ArrayList<>();
        for (Blob b : blobs) {
            if (!b.confirmed) continue;
            float half = (b.x1 - b.x0 + 1) / 2f / GW;
            float cxm = 1f - b.cx;   // mirrored so the block moves the way you do
            out.add(new TrackSink.Block(b.id, cxm - half, b.top / (float) GH, cxm + half,
                (b.bottom + 1) / (float) GH, b.armsOut));
        }
        return out;
    }

    private void findBlobs(long now) {
        // body columns: enough foreground down the column
        int minRows = (int) (GH * COL_MIN);
        for (int x = 0; x < GW; x++) {
            int c = 0;
            for (int yy = 0; yy < GH; yy++) if (fg[yy * GW + x]) c++;
            colCount[x] = c;
        }
        // runs of body columns, bridging small gaps
        List<int[]> runs = new ArrayList<>();
        int start = -1, lastBody = -100;
        for (int x = 0; x < GW; x++) {
            if (colCount[x] >= minRows) {
                if (start < 0 || x - lastBody > GAP_COLS) {
                    if (start >= 0) runs.add(new int[] {start, lastBody});
                    start = x;
                }
                lastBody = x;
            }
        }
        if (start >= 0) runs.add(new int[] {start, lastBody});
        mergeWithinDetections(runs, now);

        List<Blob> next = new ArrayList<>();
        boolean[] taken = new boolean[blobs.size()];
        for (int[] r : runs) {
            if (r[1] - r[0] + 1 < MIN_BODY_COLS) continue;
            // vertical extent and centre of the body columns
            int top = GH, bottom = -1;
            double sx = 0, sw = 0;
            for (int x = r[0]; x <= r[1]; x++) {
                sx += (x + 0.5) * colCount[x];
                sw += colCount[x];
                for (int yy = 0; yy < GH; yy++) {
                    if (fg[yy * GW + x]) { if (yy < top) top = yy; if (yy > bottom) bottom = yy; }
                }
            }
            if (bottom < 0 || bottom - top + 1 < GH * MIN_HEIGHT) continue;
            float cx = (float) (sx / sw) / GW;
            // match the nearest existing blob
            int best = -1;
            float bestD = 0.15f;
            for (int i = 0; i < blobs.size(); i++) {
                if (taken[i]) continue;
                float d = Math.abs(blobs.get(i).cx - cx);
                if (d < bestD) { bestD = d; best = i; }
            }
            Blob b;
            if (best >= 0) { taken[best] = true; b = blobs.get(best); }
            else b = reclaim(cx, now);
            b.x0 = r[0]; b.x1 = r[1]; b.top = top; b.bottom = bottom;
            b.cx = cx; b.seenAt = now;
            b.height = bottom - top + 1;
            b.reach = reach(r[0], r[1], top, bottom);
            float rr = b.reachRatio();
            boolean says = b.armsOut ? rr > ARMS_IN : rr > ARMS_OUT;
            if (says != b.armsOut) {
                if (++b.armStreak >= ARM_FRAMES) { b.armsOut = says; b.armStreak = 0; }
            } else {
                b.armStreak = 0;
            }
            next.add(b);
        }
        // a blob that vanished for a moment keeps its id for ~0.3 s; confirmed
        // ones are then remembered for LOST_KEEP_MS in case they reappear
        for (int i = 0; i < blobs.size(); i++) {
            Blob b = blobs.get(i);
            if (taken[i]) continue;
            if (now - b.seenAt < 300) next.add(b);
            else if (b.confirmed) lost.add(b);
        }
        for (int i = lost.size() - 1; i >= 0; i--) {
            if (now - lost.get(i).seenAt > LOST_KEEP_MS) lost.remove(i);
        }
        blobs.clear();
        blobs.addAll(next);
    }

    private Blob reclaim(float cx, long now) {
        Blob best = null;
        float bestD = 0.2f;
        for (Blob b : lost) {
            float d = Math.abs(b.cx - cx);
            if (d < bestD) { bestD = d; best = b; }
        }
        if (best == null) return new Blob(nextId++, now);
        lost.remove(best);
        Log.d(TrackerActivity.TAG, String.format("sil #%d back after %d ms", best.id, now - best.seenAt));
        return best;
    }

    private void mergeWithinDetections(List<int[]> runs, long now) {
        for (int i = recentDets.size() - 1; i >= 0; i--) {
            if (now - (long) recentDets.get(i)[2] > DET_MEMORY_MS) recentDets.remove(i);
        }
        for (int i = 0; i + 1 < runs.size(); ) {
            int[] a = runs.get(i), b = runs.get(i + 1);
            float ca = (a[0] + a[1] + 1) / 2f / GW, cb = (b[0] + b[1] + 1) / 2f / GW;
            boolean same = false;
            for (float[] d : recentDets) {
                if (ca >= d[0] && ca <= d[1] && cb >= d[0] && cb <= d[1]) { same = true; break; }
            }
            if (same) {
                a[1] = b[1];
                runs.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    /** Horizontal extent of foreground in the shoulder band, walking outward
     *  from the body until a gap of GAP_COLS empty columns. */
    private float reach(int x0, int x1, int top, int bottom) {
        int h = bottom - top + 1;
        int b0 = top + (int) (h * 0.12f), b1 = top + (int) (h * 0.40f);
        int left = x0, right = x1;
        int gap = 0;
        for (int x = x0 - 1; x >= 0 && gap <= GAP_COLS; x--) {
            if (bandHas(x, b0, b1)) { left = x; gap = 0; } else gap++;
        }
        gap = 0;
        for (int x = x1 + 1; x < GW && gap <= GAP_COLS; x++) {
            if (bandHas(x, b0, b1)) { right = x; gap = 0; } else gap++;
        }
        return right - left + 1;
    }

    private boolean bandHas(int x, int y0, int y1) {
        int c = 0;
        for (int yy = y0; yy <= y1; yy++) if (fg[yy * GW + x]) c++;
        return c >= 2;
    }

    private void learn(float gain, long now) {
        boolean relearn = now < relearnUntil;
        // which columns are under a confirmed person, an unconfirmed blob, or nothing
        java.util.Arrays.fill(owner, 0);
        for (Blob b : blobs) {
            int pad = (b.x1 - b.x0) / 2 + 6;   // arms reach beyond the body columns
            int v = b.confirmed ? 2 : 1;
            for (int x = Math.max(0, b.x0 - pad); x <= Math.min(GW - 1, b.x1 + pad); x++) {
                owner[x] = Math.max(owner[x], v);
            }
        }
        for (int yy = 0; yy < GH; yy++) {
            for (int x = 0; x < GW; x++) {
                int i = yy * GW + x;
                float lr;
                if (relearn) lr = 0.25f;
                else if (!fg[i]) lr = LR_BG;
                else lr = owner[x] == 2 ? LR_PERSON : LR_UNCONFIRMED;
                bg[i] += (cur[i] * gain - bg[i]) * lr;
            }
        }
    }

    /** Detector results (fractions of the camera frame, not mirrored). A blob
     *  overlapping a person box becomes a confirmed person for good. */
    synchronized void confirm(List<Detector.Person> people) {
        long now = SystemClock.elapsedRealtime();
        for (Detector.Person p : people) recentDets.add(new float[] {p.l, p.r, now});
        // each detection vouches only for the blob it overlaps most
        for (Detector.Person p : people) {
            Blob best = null;
            float bestOverlap = 0;
            for (Blob b : blobs) {
                float l = b.x0 / (float) GW, r = (b.x1 + 1) / (float) GW;
                float o = Math.min(r, p.r) - Math.max(l, p.l);
                if (o > bestOverlap) { bestOverlap = o; best = b; }
            }
            if (best != null && !best.confirmed) {
                best.confirmed = true;
                Log.d(TrackerActivity.TAG, String.format("sil #%d confirmed as a person after %d ms",
                    best.id, now - best.bornAt));
            }
        }
    }

    private void logState(long now, float gain) {
        if (now - lastLog < 500) return;
        lastLog = now;
        StringBuilder sb = new StringBuilder(String.format("sil fg %.1f%% gain %.2f blobs:", fgShare * 100, gain));
        for (Blob b : blobs) {
            sb.append(String.format(" [#%d%s x%.2f w%.2f h%.2f reach %.2f -> %s]", b.id, b.confirmed ? "" : "?",
                b.cx, (b.x1 - b.x0 + 1) / (float) GW, b.height / GH, b.reachRatio(), b.armsOut ? "OUT" : "down"));
        }
        Log.d(TrackerActivity.TAG, sb.toString());
    }
}
