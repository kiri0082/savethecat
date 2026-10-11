package com.jarvis.tracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Keep the cat dry. Characters rain down; every person the camera sees holds
 *  an umbrella that follows them left/right, opening wider with arms out.
 *  Characters landing on an umbrella slide off its edges. 40 s of rain on the
 *  cat melts it away, but after 5 s dry in a row it starts drying back, so a
 *  good run restores it fully. A timer shows how long this cat has lived.
 *  About every 10 s a red poppy drifts down; on the cat it's instantly fatal.
 *  Grayscale apart from the red umbrella and poppy, no sound - it's for kids. */
final class GameView extends View implements TrackSink {

    // ---- tuning ----
    private static final String CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%&*?+=<>/{}[]~^";
    private static final float RAIN_PER_SEC = 306f;
    private static final float MELT_SECONDS = 10f;
    private static final float WET_WINDOW = 0.25f;      // a hit keeps the cat "getting wet" this long
    private static final float POPPY_EVERY = 10f;       // seconds, +/- 2 at random
    private static final int POPPY = 0xffc8241f, POPPY_DARK = 0xff8a1210, POPPY_EYE = 0xff141414;
    private static final float DRY_AFTER = 5f;          // seconds dry in a row before it recovers
    private static final float WET_RATE = 0.25f;        // melt progress per second of rain (40 s to melt)
    private static final float DRY_RATE = 1.0f;         // wet seconds undone per second
    private static final float CAT_SPEED = 0.075f;      // screen widths per second
    private static final float ERRATIC_RAMP_S = 150f;   // play time to reach full erratic-ness
    private static final float UMB_HALF = 0.1375f, UMB_HALF_WIDE = 0.17f;  // screen widths
    private static final long  MELTED_PAUSE_MS = 5000;
    // camera x 0.12..0.88 spans the whole screen, so kids needn't reach the edges
    private static final float REACH_LO = 0.12f, REACH_HI = 0.88f;

    // ---- grays ----
    private static final int BG = 0xff0e0e0e;
    private static final int CAT_FILL = 0xffb8b8b8, CAT_LINE = 0xff5c5c5c, CAT_DARK = 0xff2a2a2a;
    // the one colour on screen (owner's call): a slightly dark red umbrella
    private static final int UMB_FILL = 0xff9c2626, UMB_RIB = 0xff5a1010, HANDLE = 0xff9a9a9a;
    private static final int TEXT_MAIN = 0xffbdbdbd, TEXT_DIM = 0xff5a5a5a;

    private enum State { ATTRACT, PLAYING, MELTED }

    private final Random rnd = new Random();
    private final Paint[] glyph = new Paint[4];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final char[][] batchChars = new char[4][256];
    private final float[][] batchPos = new float[4][512];
    private final int[] batchN = new int[4];
    private final float[] glyphHalf = new float[4];   // monospace: centre each glyph
    private final char[] one = new char[1];

    // shared with the tracker thread
    private volatile List<Block> players = new ArrayList<>();
    private volatile String status = "STARTING";
    private volatile float detFps;

    // ---- world (UI thread) ----
    private int w, h;
    private float groundY, canopyY;
    private State state = State.ATTRACT;
    private long stateAt;
    private float playTime, wet, rainDebt;
    private float catLife;            // how long this cat has lived, counting only play time
    private float poppyIn = POPPY_EVERY;
    private boolean poppyGotIt;       // this cat was lost to a poppy, not the rain
    private static final float POPPY_PUDDLE_S = 2f;   // a landed poppy melts this long
    private final List<float[]> poppyPuddles = new ArrayList<>();   // {x, age}
    private final List<Shard> shards = new ArrayList<>();          // the exploded cat
    private float lastHitAgo = 99f;
    private long lastNanos, lastLog;
    private int frames;

    private final List<Drop> drops = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final Map<Integer, Umbrella> umbrellas = new HashMap<>();
    // per-frame copy for the per-drop checks: iterating the map from ~1000
    // drops allocated ~60k iterators/s and GC held the game near 22 fps
    private Umbrella[] umbArr = new Umbrella[0];
    private int umbN;
    private final Cat cat = new Cat();

    GameView(Context ctx) {
        super(ctx);
        int[] sizes = {9, 11, 14, 16};
        int[] grays = {0xff3c3c3c, 0xff5e5e5e, 0xff858585, 0xffadadad};  // bigger = nearer = lighter
        for (int i = 0; i < 4; i++) {
            glyph[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            glyph[i].setTypeface(Typeface.MONOSPACE);
            glyph[i].setTextSize(sizes[i]);
            glyph[i].setColor(grays[i]);
            glyphHalf[i] = glyph[i].measureText("M") / 2;
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(TEXT_MAIN);
        text.setTextSize(44);
        text.setLetterSpacing(0.08f);
        small.setTypeface(Typeface.MONOSPACE);
        small.setTextSize(20);
        small.setColor(0xff333333);
    }

    // ---- TrackSink ----
    @Override public void setBlocks(List<Block> b) { players = b; }
    @Override public void setStatus(String s) { status = s; }
    @Override public void setStats(float detectionsPerSec, float msEach) { detFps = detectionsPerSec; }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh;
        groundY = h - 70;
        canopyY = h - 401;   // hem ~189 px above the cat's ears
        cat.reset(w * 0.5f);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        step(dt);
        render(c);
        frames++;
        logState();
        logUmbrellas();
        postInvalidateOnAnimation();
    }

    // =====================================================================
    // simulation
    // =====================================================================

    private void step(float dt) {
        List<Block> ps = players;
        long nowMs = SystemClock.elapsedRealtime();

        // state machine: someone in view = play; nobody = attract (paused)
        if (state == State.MELTED) {
            if (nowMs - stateAt > MELTED_PAUSE_MS) {
                wet = 0; playTime = 0; catLife = 0; poppyGotIt = false; exploded = false;
                cat.reset(rnd.nextBoolean() ? -80 : w + 80);
                setState(ps.isEmpty() ? State.ATTRACT : State.PLAYING);
            }
        } else if (ps.isEmpty() && state == State.PLAYING) {
            setState(State.ATTRACT);
        } else if (!ps.isEmpty() && state == State.ATTRACT) {
            setState(State.PLAYING);
        }
        if (state == State.PLAYING) { playTime += dt; catLife += dt; }

        updateUmbrellas(ps, dt);
        if (state != State.MELTED) cat.step(dt, erratic());

        // rain: steady
        rainDebt += RAIN_PER_SEC * dt;
        while (rainDebt >= 1) { rainDebt--; drops.add(new Drop(false)); }
        poppyIn -= dt;
        if (poppyIn <= 0) {
            poppyIn = POPPY_EVERY - 2 + rnd.nextFloat() * 4;
            drops.add(new Drop(true));
        }

        lastHitAgo += dt;
        // swap-remove: ~700 drops, hundreds finishing a second
        for (int i = 0; i < drops.size(); ) {
            if (drops.get(i).step(dt)) {
                int last = drops.size() - 1;
                drops.set(i, drops.get(last));
                drops.remove(last);
            } else {
                i++;
            }
        }
        // landed poppies melt for POPPY_PUDDLE_S; a cat stepping in one explodes
        for (int i = poppyPuddles.size() - 1; i >= 0; i--) {
            float[] p = poppyPuddles.get(i);
            p[1] += dt;
            if (p[1] > POPPY_PUDDLE_S) { poppyPuddles.remove(i); continue; }
            float r = poppyRadius(p[1]);
            if (state == State.PLAYING && Math.abs(cat.x - p[0]) < Cat.BW * 0.45f + r) {
                poppyPuddles.remove(i);
                explodeCat("cat stepped in a poppy");
            }
        }
        for (int i = shards.size() - 1; i >= 0; i--) {
            Shard sh = shards.get(i);
            sh.age += dt; sh.x += sh.vx * dt; sh.y += sh.vy * dt; sh.vy += 700 * dt; sh.rot += sh.vr * dt;
            if (sh.y > groundY + 18) { sh.y = groundY + 18; sh.vy *= -0.3f; sh.vx *= 0.6f; }
            if (sh.age > Shard.LIFE) shards.remove(i);
        }
        for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            s.age += dt; s.x += s.vx * dt; s.y += s.vy * dt; s.vy += 900 * dt;
            if (s.age > s.life) it.remove();
        }

        if (state == State.PLAYING && lastHitAgo >= DRY_AFTER && wet > 0) {
            wet = Math.max(0, wet - DRY_RATE * dt);
        }
        if (state == State.PLAYING && lastHitAgo < WET_WINDOW) {
            wet += WET_RATE * dt;
            if (wet >= MELT_SECONDS) {
                wet = MELT_SECONDS;
                for (int i = 0; i < 40; i++) splash(cat.x, groundY - 30, 0xff9a9a9a);
                Log.d(TrackerActivity.TAG, "game cat lived " + clock(catLife));
                setState(State.MELTED);
            }
        }
    }

    private boolean exploded;   // cat burst into characters (poppy), so no melt puddle

    /** The cat bursts into characters that fly out and rain back down. */
    private void explodeCat(String why) {
        if (state != State.PLAYING) return;
        float m = cat.melt(), squash = 1 - 0.55f * m;
        float bodyTop = groundY - (Cat.BH + 26) * squash;
        for (int i = 0; i < 60; i++) {
            Shard sh = new Shard();
            sh.ch = CHARS.charAt(rnd.nextInt(CHARS.length()));
            sh.x = cat.x + (rnd.nextFloat() - 0.5f) * Cat.BW;
            sh.y = bodyTop + rnd.nextFloat() * (groundY - bodyTop);
            double a = Math.toRadians(200 + rnd.nextFloat() * 140);   // mostly upward fan
            float sp = 300 + rnd.nextFloat() * 500;
            sh.vx = (float) Math.cos(a) * sp;
            sh.vy = (float) Math.sin(a) * sp;
            sh.vr = (rnd.nextFloat() - 0.5f) * 720;
            sh.size = 18 + rnd.nextInt(16);
            shards.add(sh);
        }
        wet = MELT_SECONDS;
        poppyGotIt = true;
        exploded = true;
        Log.d(TrackerActivity.TAG, "game " + why + " - exploded, lived " + clock(catLife));
        setState(State.MELTED);
    }

    private static float poppyRadius(float age) {
        return 52 * (1 - 0.6f * age / POPPY_PUDDLE_S);   // 2x: easier for the cat to blunder into
    }

    private static final class Shard {
        static final float LIFE = 2.2f;
        char ch;
        float x, y, vx, vy, rot, vr, age;
        int size;
    }

    private float erratic() { return Math.min(1f, playTime / ERRATIC_RAMP_S); }

    private void setState(State s) {
        Log.d(TrackerActivity.TAG, "game " + state + " -> " + s + String.format(" (wet %.1fs, played %.0fs)", wet, playTime));
        state = s;
        stateAt = SystemClock.elapsedRealtime();
    }

    private void updateUmbrellas(List<Block> ps, float dt) {
        for (Umbrella u : umbrellas.values()) u.present = false;
        for (Block b : ps) {
            Umbrella u = umbrellas.get(b.id);
            float cx = (b.l + b.r) / 2;
            float sx = (cx - REACH_LO) / (REACH_HI - REACH_LO) * w;
            sx = Math.max(0, Math.min(w, sx));
            if (u == null) {
                u = new Umbrella(sx);
                umbrellas.put(b.id, u);
            }
            u.present = true;
            u.targetX = sx;
            u.wide = b.armsOut;
        }
        for (Iterator<Umbrella> it = umbrellas.values().iterator(); it.hasNext(); ) {
            Umbrella u = it.next();
            u.step(dt);
            if (!u.present && u.alpha < 0.02f) { u.gone = true; it.remove(); }
        }
        if (umbArr.length < umbrellas.size()) umbArr = new Umbrella[umbrellas.size() * 2];
        umbN = 0;
        for (Umbrella u : umbrellas.values()) umbArr[umbN++] = u;
    }

    private void splash(float x, float y, int color) {
        Spark s = new Spark();
        s.x = x; s.y = y; s.color = color;
        s.vx = (rnd.nextFloat() - 0.5f) * 260;
        s.vy = -120 - rnd.nextFloat() * 220;
        s.life = 0.25f + rnd.nextFloat() * 0.3f;
        sparks.add(s);
    }

    /** One falling character. Falls, or slides along an umbrella's canopy
     *  toward the nearer edge and drops off it. */
    private final class Drop {
        final char ch = CHARS.charAt(rnd.nextInt(CHARS.length()));
        final int size = rnd.nextInt(4);
        // a poppy: falls slower and sways so kids see it coming; on the cat
        // during play it is instantly fatal
        final boolean poppy;
        float x = rnd.nextFloat() * w, y = -40;
        float vx = 0, vy;
        float age, spin = rnd.nextFloat() * 360;
        int bounces;          // characters hop off the floor a couple of times

        Drop(boolean poppy) {
            this.poppy = poppy;
            vy = poppy ? 150 : 230 + size * 55 + rnd.nextFloat() * 60;
            if (poppy) x = w * (0.1f + 0.8f * rnd.nextFloat());
        }
        Umbrella on;          // sliding on this umbrella
        float rel;            // position on it, -1..1 of its half width

        /** @return true when this drop is finished */
        boolean step(float dt) {
            if (on != null) {
                if (on.gone || on.alpha < 0.3f) { on = null; return false; }
                float half = on.half();
                rel += Math.signum(rel) * (300f / half) * dt;   // slide outward
                if (Math.abs(rel) >= 1) {
                    x = on.x + Math.signum(rel) * half;
                    y = canopyY;
                    vx = Math.signum(rel) * 70;
                    vy = 90;
                    on = null;
                    return false;
                }
                x = on.x + rel * half;
                y = on.surfaceY(rel) - 4;
                return false;
            }
            age += dt;
            if (poppy) {
                x += (vx + (float) Math.sin(age * 2.2f) * 60) * dt;
                y += vy * dt;
                spin += 40 * dt;
            } else {
                x += vx * dt;
                y += vy * dt;
                vy += (bounces > 0 ? 1600 : 60) * dt;   // real gravity once it has bounced
            }
            // land on an umbrella?
            for (int ui = 0; ui < umbN; ui++) {
                Umbrella u = umbArr[ui];
                if (u.alpha < 0.5f) continue;
                float half = u.half(), dx = x - u.x;
                // drops just shed off an edge (vx != 0) mustn't re-land on it
                if (Math.abs(dx) < half * (vx == 0 ? 0.98f : 0.9f)) {
                    float r = dx / half;
                    if (y >= u.surfaceY(r) - 4 && y <= canopyY + 10) {
                        on = u;
                        rel = r == 0 ? (rnd.nextBoolean() ? 0.01f : -0.01f) : r;
                        return false;
                    }
                }
            }
            // hit the cat?
            // bounced characters are just floor splash: they don't wet the cat
            if (state != State.MELTED && bounces == 0 && cat.hit(x, y)) {
                if (poppy) {
                    for (int i = 0; i < 12; i++) splash(x, y, POPPY);
                    if (state == State.PLAYING) explodeCat("poppy hit the cat");
                    return true;
                }
                lastHitAgo = 0;
                splash(x, y, 0xff8c8c8c);
                splash(x, y, 0xff8c8c8c);
                return true;
            }
            if (y >= groundY + 20) {
                if (poppy) {
                    poppyPuddles.add(new float[] {x, 0});
                    for (int i = 0; i < 6; i++) splash(x, groundY + 16, POPPY);
                    return true;
                }
                splash(x, groundY + 20, 0xff4a4a4a);
                if (bounces < 2) {
                    bounces++;
                    y = groundY + 20;
                    vy = -Math.abs(vy) * (bounces == 1 ? 0.32f : 0.2f) * (0.7f + 0.6f * rnd.nextFloat());
                    vx = (rnd.nextFloat() - 0.5f) * 180;
                    splash(x, groundY + 20, 0xff5e5e5e);
                    return false;
                }
                return true;
            }
            return x < -60 || x > w + 60;
        }
    }

    private static final class Spark {
        float x, y, vx, vy, age, life;
        int color;
    }

    /** One player's umbrella. x goes through a One-Euro filter: heavy
     *  smoothing when the player is nearly still (hides detector wobble),
     *  light smoothing when they move fast (keeps lag low). */
    private final class Umbrella {
        static final float MIN_CUTOFF = 0.9f;   // Hz, at rest
        static final float BETA = 0.004f;       // cutoff gain per px/s of speed
        static final float D_CUTOFF = 1.0f;     // Hz, for the speed estimate
        float x, targetX, open, alpha;   // open: 0 narrow .. 1 wide
        float speed;                      // filtered px/s
        boolean wide, present = true, gone;

        Umbrella(float x) { this.x = x; this.targetX = x; }

        private float lowpass(float cutoff, float dt) {
            float tau = 1f / (2f * (float) Math.PI * cutoff);
            return 1f / (1f + tau / dt);
        }

        void step(float dt) {
            if (dt > 0) {
                float raw = (targetX - x) / dt;
                speed += (raw - speed) * lowpass(D_CUTOFF, dt);
                x += (targetX - x) * lowpass(MIN_CUTOFF + BETA * Math.abs(speed), dt);
            }
            open += ((wide ? 1 : 0) - open) * Math.min(1, dt * 7);
            alpha += ((present ? 1 : 0) - alpha) * Math.min(1, dt * 5);
        }

        float half() { return w * (UMB_HALF + (UMB_HALF_WIDE - UMB_HALF) * open); }

        /** canopy top at rel -1..1; a flat-ish dome */
        float surfaceY(float rel) {
            float dome = half() * 0.32f;
            return canopyY - dome * (float) Math.sqrt(Math.max(0, 1 - rel * rel));
        }
    }

    /** The cat: one speed, sudden stops and turns, more of both over time. */
    private final class Cat {
        float x, dir = 1, stopFor, phase, alpha;
        static final float BW = 120, BH = 62;   // body size

        void reset(float startX) {
            x = startX;
            dir = startX < w / 2f ? 1 : -1;
            stopFor = 0;
            alpha = 0;
        }

        void step(float dt, float erratic) {
            alpha += (1 - alpha) * Math.min(1, dt * 2);
            if (stopFor > 0) {
                stopFor -= dt;
            } else {
                x += dir * CAT_SPEED * w * dt;
                phase += dt * 9;
                float pTurn = (0.12f + 0.45f * erratic) * dt;
                float pStop = (0.10f + 0.30f * erratic) * dt;
                if (rnd.nextFloat() < pTurn) dir = -dir;
                if (rnd.nextFloat() < pStop) stopFor = 0.4f + rnd.nextFloat() * (1.4f - 0.6f * erratic);
            }
            float margin = 110;
            if (x < margin) { dir = 1; if (x < margin - 40) x += CAT_SPEED * w * dt; }
            if (x > w - margin) { dir = -1; if (x > w - margin + 40) x -= CAT_SPEED * w * dt; }
        }

        float melt() { return wet / MELT_SECONDS; }

        boolean hit(float px, float py) {
            float m = melt();
            float top = groundY - (BH + 46) * (1 - 0.55f * m);
            return py >= top && py <= groundY && px >= x - BW * 0.62f && px <= x + BW * 0.62f;
        }
    }

    // =====================================================================
    // drawing
    // =====================================================================

    private void render(Canvas c) {
        c.drawColor(BG);
        // one drawPosText per size: ~700 single drawText calls held it to ~32 fps
        for (int k = 0; k < 4; k++) batchN[k] = 0;
        for (Drop d : drops) {
            if (d.poppy) continue;
            int k = d.size, n = batchN[k];
            if (n >= batchChars[k].length) {
                batchChars[k] = java.util.Arrays.copyOf(batchChars[k], n * 2);
                batchPos[k] = java.util.Arrays.copyOf(batchPos[k], n * 4);
            }
            batchChars[k][n] = d.ch;
            batchPos[k][2 * n] = d.x - glyphHalf[k];
            batchPos[k][2 * n + 1] = d.y;
            batchN[k] = n + 1;
        }
        for (int k = 0; k < 4; k++) {
            if (batchN[k] > 0) c.drawPosText(batchChars[k], 0, batchN[k], batchPos[k], glyph[k]);
        }
        for (Drop d : drops) if (d.poppy) drawPoppy(c, d.x, d.y, d.spin);
        line.setStrokeWidth(2);
        fill.setColor(0xff1b1b1b);
        c.drawRect(0, groundY + 22, w, h, fill);          // floor
        drawPuddle(c);
        drawPoppyPuddles(c);
        if (state != State.MELTED) drawCat(c);
        drawShards(c);
        for (Umbrella u : umbrellas.values()) drawUmbrella(c, u);
        for (Spark s : sparks) {
            fill.setColor(s.color);
            fill.setAlpha((int) (255 * (1 - s.age / s.life)));
            c.drawCircle(s.x, s.y, 3, fill);
        }
        fill.setAlpha(255);
        drawText(c);
    }

    private void drawUmbrella(Canvas c, Umbrella u) {
        int a = (int) (u.alpha * 255);
        float half = u.half(), top = u.surfaceY(0);
        // handle: shaft from the canopy down, J hook at the bottom
        line.setColor(HANDLE); line.setAlpha(a); line.setStrokeWidth(7);
        c.drawLine(u.x, top, u.x, canopyY + 133, line);
        rect.set(u.x - 28, canopyY + 107, u.x, canopyY + 159);   // arc's right end on the shaft
        c.drawArc(rect, 0, 180, false, line);
        // canopy: dome plus scalloped hem
        path.reset();
        path.moveTo(u.x - half, canopyY);
        for (int i = -20; i <= 20; i++) {
            float r = i / 20f;
            path.lineTo(u.x + r * half, u.surfaceY(r));
        }
        int scallops = u.open > 0.5f ? 6 : 4;
        float sw = 2 * half / scallops;
        for (int i = scallops - 1; i >= 0; i--) {
            float x0 = u.x - half + (i + 1) * sw, x1 = u.x - half + i * sw;
            path.quadTo((x0 + x1) / 2, canopyY - sw * 0.28f, x1, canopyY);
        }
        path.close();
        fill.setColor(UMB_FILL); fill.setAlpha(a);
        c.drawPath(path, fill);
        line.setColor(UMB_RIB); line.setAlpha(a); line.setStrokeWidth(3);
        for (int i = 1; i < scallops; i++) {
            float rx = -1 + 2f * i / scallops;
            c.drawLine(u.x, top, u.x + rx * half, canopyY - 2, line);
        }
        c.drawPath(path, line);
        // tip
        line.setStrokeWidth(5);
        c.drawLine(u.x, top, u.x, top - 18, line);
        fill.setAlpha(255);
        line.setAlpha(255);
    }

    private void drawCat(Canvas c) {
        float m = cat.melt();
        float squash = 1 - 0.55f * m;                       // melts downward
        int a = (int) (cat.alpha * (1 - 0.65f * m) * 255);
        float bw = Cat.BW * (1 + 0.25f * m), bh = Cat.BH * squash;
        float cx = cat.x, base = groundY;
        float d = cat.dir;
        boolean walking = cat.stopFor <= 0;

        line.setColor(CAT_LINE); line.setAlpha(a); line.setStrokeWidth(5);
        // legs
        float legH = 26 * squash;
        for (int i = 0; i < 4; i++) {
            float lx = cx - bw * 0.36f + i * bw * 0.24f;
            float swing = walking ? (float) Math.sin(cat.phase + (i % 2) * Math.PI) * 9 * squash : 0;
            c.drawLine(lx, base - legH, lx + swing, base, line);
        }
        // tail: a curve up and back
        path.reset();
        float tx = cx - d * bw * 0.48f, ty = base - legH - bh * 0.6f;
        path.moveTo(tx, ty);
        path.quadTo(tx - d * 46, ty - 10 * squash, tx - d * 38, ty - 58 * squash);
        line.setStrokeWidth(8);
        c.drawPath(path, line);
        // body
        rect.set(cx - bw / 2, base - legH - bh, cx + bw / 2, base - legH + 6);
        fill.setColor(CAT_FILL); fill.setAlpha(a);
        c.drawOval(rect, fill);
        line.setStrokeWidth(3);
        c.drawOval(rect, line);
        // head with ears
        float hr = 30 * (1 - 0.4f * m);
        float hx = cx + d * bw * 0.46f, hy = base - legH - bh - hr * 0.35f;
        path.reset();
        path.moveTo(hx - hr * 0.85f, hy - hr * 0.35f);
        path.lineTo(hx - hr * 0.55f, hy - hr * 1.45f);
        path.lineTo(hx - hr * 0.05f, hy - hr * 0.75f);
        path.moveTo(hx + hr * 0.85f, hy - hr * 0.35f);
        path.lineTo(hx + hr * 0.55f, hy - hr * 1.45f);
        path.lineTo(hx + hr * 0.05f, hy - hr * 0.75f);
        c.drawPath(path, fill);
        c.drawPath(path, line);
        c.drawCircle(hx, hy, hr, fill);
        c.drawCircle(hx, hy, hr, line);
        // eyes (look the way it walks) + nose
        fill.setColor(CAT_DARK); fill.setAlpha(a);
        c.drawCircle(hx + d * hr * 0.15f - hr * 0.3f, hy - hr * 0.1f, 4, fill);
        c.drawCircle(hx + d * hr * 0.15f + hr * 0.3f, hy - hr * 0.1f, 4, fill);
        c.drawCircle(hx + d * hr * 0.35f, hy + hr * 0.25f, 3, fill);
        // drips while melting
        if (m > 0.05f) {
            fill.setColor(CAT_FILL); fill.setAlpha(a);
            int n = (int) (m * 6);
            for (int i = 0; i < n; i++) {
                float dx = cx - bw * 0.4f + (i * 37 % (int) bw);
                float len = 8 + ((i * 13) % 14) * m;
                rect.set(dx - 4, base - legH, dx + 4, base - legH + len);
                c.drawRoundRect(rect, 4, 4, fill);
            }
        }
        fill.setAlpha(255);
        line.setAlpha(255);
    }

    /** five overlapping red petals round a dark centre, ~40 px across */
    private void drawPoppy(Canvas c, float x, float y, float spin) {
        c.save();
        c.rotate(spin, x, y);
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(i * 72);
            float px = x + (float) Math.cos(a) * 10, py = y + (float) Math.sin(a) * 10;
            fill.setColor(i % 2 == 0 ? POPPY : POPPY_DARK);
            c.drawCircle(px, py, 12, fill);
        }
        fill.setColor(POPPY);
        c.drawCircle(x, y, 9, fill);
        fill.setColor(POPPY_EYE);
        c.drawCircle(x, y, 5, fill);
        c.restore();
    }

    private void drawPoppyPuddles(Canvas c) {
        for (float[] p : poppyPuddles) {
            float k = p[1] / POPPY_PUDDLE_S, r = poppyRadius(p[1]);
            fill.setColor(POPPY);
            fill.setAlpha((int) (230 * (1 - k * 0.7f)));
            rect.set(p[0] - r * 1.4f, groundY + 10, p[0] + r * 1.4f, groundY + 10 + r * 0.8f);
            c.drawOval(rect, fill);
            fill.setColor(POPPY_DARK);
            c.drawCircle(p[0], groundY + 10 + r * 0.4f, r * 0.3f, fill);
        }
        fill.setAlpha(255);
    }

    private void drawShards(Canvas c) {
        for (Shard sh : shards) {
            text.setTextSize(sh.size);
            text.setColor(0xffb8b8b8);
            text.setAlpha((int) (255 * Math.max(0, 1 - sh.age / Shard.LIFE)));
            one[0] = sh.ch;
            c.save();
            c.rotate(sh.rot, sh.x, sh.y);
            c.drawText(one, 0, 1, sh.x, sh.y, text);
            c.restore();
        }
        text.setAlpha(255);
    }

    private void drawPuddle(Canvas c) {
        if (exploded) return;
        float m = state == State.MELTED ? 1 : cat.melt();
        if (m <= 0.02f) return;
        float pw = Cat.BW * (0.4f + 1.4f * m);
        fill.setColor(0xff7a7a7a);
        fill.setAlpha((int) (60 + 120 * m));
        rect.set(cat.x - pw / 2, groundY + 6, cat.x + pw / 2, groundY + 6 + 14 + 10 * m);
        c.drawOval(rect, fill);
        fill.setAlpha(255);
    }

    private void drawText(Canvas c) {
        String st = status;
        float cx = w / 2f;
        // how long this cat has lived; frozen while it's melted
        text.setTextSize(64); text.setColor(state == State.PLAYING ? TEXT_MAIN : TEXT_DIM);
        c.drawText(clock(catLife), cx, 92, text);
        if (st != null) {
            text.setTextSize(30); text.setColor(TEXT_DIM);
            c.drawText(st, cx, 150, text);
        } else if (state == State.ATTRACT) {
            text.setTextSize(48); text.setColor(TEXT_MAIN);
            c.drawText("STAND IN FRONT OF THE CAMERA", cx, h * 0.3f, text);
            text.setTextSize(30); text.setColor(TEXT_DIM);
            c.drawText("YOUR UMBRELLA KEEPS THE CAT DRY", cx, h * 0.3f + 56, text);
        } else if (state == State.MELTED) {
            text.setTextSize(52); text.setColor(TEXT_MAIN);
            c.drawText(poppyGotIt ? "OH NO, A POPPY!" : "OH NO, THE CAT MELTED!", cx, h * 0.3f, text);
            text.setTextSize(30); text.setColor(TEXT_DIM);
            c.drawText("IT LASTED " + clock(catLife) + "  \u00b7  A NEW CAT IS ON ITS WAY...", cx, h * 0.3f + 56, text);
        } else if (playTime < 12) {
            text.setTextSize(30); text.setColor(TEXT_DIM);
            c.drawText("STRETCH YOUR ARMS OUT FOR A BIGGER UMBRELLA", cx, 150, text);
        }
        // tiny tuning readout, bottom-left
        c.drawText(String.format("%.1f/s  wet %.1fs", detFps, wet), 16, h - 14, small);
    }

    private long lastUmbLog;

    /** 15 Hz trace of target vs drawn umbrella x, for chasing jitter */
    private void logUmbrellas() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUmbLog < 66 || umbrellas.isEmpty()) return;
        lastUmbLog = now;
        StringBuilder sb = new StringBuilder("umb");
        for (Umbrella u : umbrellas.values()) sb.append(String.format(" t%.0f x%.0f v%.0f", u.targetX, u.x, u.speed));
        Log.d(TrackerActivity.TAG, sb.toString());
    }

    private static String clock(float s) {
        int t = (int) s;
        return t / 60 + ":" + (t % 60 < 10 ? "0" : "") + t % 60;
    }

    private void logState() {
        long now = SystemClock.elapsedRealtime();
        if (lastLog == 0) lastLog = now;
        if (now - lastLog < 3000) return;
        StringBuilder u = new StringBuilder();
        for (Umbrella um : umbrellas.values()) {
            u.append(String.format(" [x%.0f %s a%.1f]", um.x, um.wide ? "WIDE" : "narrow", um.alpha));
        }
        Log.d(TrackerActivity.TAG, String.format("game %s draw %.0f fps, drops %d, cat x%.0f%s, wet %.1fs%s, lived %s, umbrellas%s",
            state, frames * 1000f / (now - lastLog), drops.size(), cat.x, cat.stopFor > 0 ? " (stopped)" : "",
            wet, lastHitAgo >= DRY_AFTER && wet > 0 ? " drying" : "", clock(catLife), u));
        frames = 0;
        lastLog = now;
    }
}
