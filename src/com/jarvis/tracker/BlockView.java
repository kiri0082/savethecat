package com.jarvis.tracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Dark field with one block per tracked person. Blocks ease toward each new
 *  detection (detections arrive a few times a second; drawing runs at 60) and
 *  fade when the person is lost. Teal = arms down, amber = arms out. */
final class BlockView extends View implements TrackSink {

    private static final int BG = 0xff04121a;
    private static final int TEAL = 0xff1fe0e0;
    private static final int AMBER = 0xffffb238;
    private static final float EASE = 0.45f;   // targets now update 30x/s

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    // shared with the detector thread
    private volatile List<Block> targets = new ArrayList<>();
    private volatile String status = "STARTING";
    private volatile float detFps, ms;

    /** what's on screen, per track id: l t r b alpha armsOut(0/1) - UI thread */
    private final Map<Integer, float[]> shown = new HashMap<>();

    BlockView(Context ctx) {
        super(ctx);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(4);
        // a cheap wide stroke instead of BlurMaskFilter: keeps hardware drawing
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(16);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(26);
        text.setLetterSpacing(0.15f);
        label.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        label.setTextSize(30);
        label.setLetterSpacing(0.2f);
        frame.setColor(0x55125a66);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2);
    }

    @Override public void setBlocks(List<Block> b) { targets = b; postInvalidateOnAnimation(); }

    @Override public void setStatus(String s) { status = s; postInvalidate(); }

    @Override public void setStats(float detFps, float ms) { this.detFps = detFps; this.ms = ms; }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(BG);
        float pad = 60, fw = w - 2 * pad, fh = h - 2 * pad;
        c.drawRoundRect(pad, pad, w - pad, h - pad, 26, 26, frame);

        // ease known blocks toward their targets; unseen ones fade out
        List<Block> tg = targets;
        Map<Integer, Block> byId = new HashMap<>();
        for (Block b : tg) byId.put(b.id, b);
        for (Block b : tg) {
            if (!shown.containsKey(b.id)) shown.put(b.id, new float[] {b.l, b.t, b.r, b.b, 0, 0});
        }
        boolean moving = false;
        for (Iterator<Map.Entry<Integer, float[]>> it = shown.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, float[]> e = it.next();
            float[] s = e.getValue();
            Block b = byId.get(e.getKey());
            if (b != null) {
                s[0] += (b.l - s[0]) * EASE; s[1] += (b.t - s[1]) * EASE;
                s[2] += (b.r - s[2]) * EASE; s[3] += (b.b - s[3]) * EASE;
                s[5] = b.armsOut ? 1 : 0;
            }
            float want = b != null ? 1 : 0;
            s[4] += (want - s[4]) * 0.15f;
            if (b == null && s[4] < 0.02f) { it.remove(); continue; }
            moving |= Math.abs(want - s[4]) > 0.01f || b != null;

            rect.set(pad + s[0] * fw, pad + s[1] * fh, pad + s[2] * fw, pad + s[3] * fh);
            int color = s[5] > 0 ? AMBER : TEAL;
            int a = (int) (s[4] * 255);
            halo.setColor(color); halo.setAlpha(a / 6);
            c.drawRoundRect(rect, 18, 18, halo);
            fill.setColor(color); fill.setAlpha(a / 7);
            c.drawRoundRect(rect, 18, 18, fill);
            edge.setColor(color); edge.setAlpha(a);
            c.drawRoundRect(rect, 18, 18, edge);
            if (s[5] > 0) {
                label.setColor(AMBER); label.setAlpha(a);
                c.drawText("ARMS OUT", rect.left + 6, Math.max(pad + 40, rect.top - 16), label);
            }
        }

        String st = status;
        if (st != null) {
            text.setColor(AMBER);
            c.drawText(st, pad + 24, pad + 50, text);
        } else {
            text.setColor(TEAL);
            text.setAlpha(150);
            String who = tg.isEmpty() ? "NO ONE IN VIEW" : tg.size() + (tg.size() == 1 ? " PERSON" : " PEOPLE");
            c.drawText(String.format("TRACK · %s · %.1f/S · %.0f MS", who, detFps, ms),
                pad + 24, pad + 50, text);
            text.setAlpha(255);
        }
        if (moving) postInvalidateOnAnimation();
    }
}
