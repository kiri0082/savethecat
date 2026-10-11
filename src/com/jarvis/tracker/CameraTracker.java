package com.jarvis.tracker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Webcam at 320x240 YUV -> person detectors -> tracked people.
 *
 *  The detector (~7/s, each result ~270 ms old) says who is where and
 *  whether their arms are out. Each person's centre runs through an
 *  alpha-beta (position + velocity) filter fed with those detections, and is
 *  published 30x/s predicted forward to the newest camera frame - smooth,
 *  and it makes up the detector's lag.
 *
 *  Frame-differencing nudges (motion=true) are kept for comparison but off:
 *  a trace showed the motion centroid dragging a still player's box ~14% of
 *  the frame off them, which was most of the visible jitter.
 *
 *  The camera keeps its 30 fps. TFLite on this box's 32-bit build runs each
 *  inference on one core whatever numThreads says (~220 ms), so instead we
 *  run several single-threaded detectors side by side, each taking the
 *  newest frame when it frees up: same latency, N times the update rate. */
final class CameraTracker {

    static final int W = 320, H = 240;

    private final Context ctx;
    private final TrackSink view;
    private HandlerThread camThread;
    private Handler camBg;
    private CameraDevice cam;
    private CameraCaptureSession session;
    private ImageReader reader;
    private final Worker[] workers;

    private final List<Track> tracks = new ArrayList<>();  // guarded by this

    // motion grid: 4x4-cell luma averages of each frame (camera thread)
    static final int GW = 80, GH = 60;
    private static final int DIFF = 18;        // luma change that counts as motion
    private static final int MIN_CELLS = 6;    // per person, to ignore sensor noise
    private static final float GAIN = 0.35f;   // fraction of the gap closed per frame
    private static final float MAX_STEP = 0.04f;
    private final byte[] grid = new byte[GW * GH], prevGrid = new byte[GW * GH];
    private boolean havePrev;
    private int motionMoves;
    private int nextId = 1;
    private long newestApplied;   // frame time of the last result used

    private int camFrames, detRuns;
    private long statsStart, detNanos, prepNanos, inferNanos;

    /** One detector on its own thread with its own frame buffer. */
    private final class Worker {
        final HandlerThread thread;
        final Handler bg;
        final Detector.Frame frame = new Detector.Frame();
        volatile Detector detector;
        volatile boolean busy;
        long frameAt;

        Worker(int i) {
            thread = new HandlerThread("detector" + i);
            thread.start();
            bg = new Handler(thread.getLooper());
            bg.post(() -> {
                try {
                    long t0 = SystemClock.elapsedRealtime();
                    detector = new Detector(ctx, 1);
                    Log.d(TrackerActivity.TAG, "detector " + i + " ready in "
                        + (SystemClock.elapsedRealtime() - t0) + " ms");
                } catch (Throwable e) {
                    Log.e(TrackerActivity.TAG, "detector failed", e);
                    view.setStatus("DETECTOR FAILED TO LOAD");
                }
            });
        }

        void run() {
            long t0 = System.nanoTime();
            List<Detector.Person> people = detector.detect(frame);
            long took = System.nanoTime() - t0;
            busy = false;   // camera may hand this worker the next frame
            synchronized (CameraTracker.this) {
                detNanos += took;
                prepNanos += detector.prepNanos; inferNanos += detector.inferNanos;
                detector.prepNanos = 0; detector.inferNanos = 0;
                detRuns++;
                if (sil != null) {
                    sil.confirm(people);   // the detector only vouches that blobs are people
                } else if (frameAt > newestApplied) {   // a slower worker can finish after a newer frame: drop it
                    newestApplied = frameAt;
                    update(people, frameAt);
                }
                stats();
            }
        }

        void stop() {
            bg.post(() -> { if (detector != null) detector.close(); });
            thread.quitSafely();
        }
    }

    private final boolean useMotion;
    private final Silhouette sil;   // null = the older detector-driven tracking
    private long latestTs;   // newest camera frame time: "now" for predictions

    CameraTracker(Context ctx, TrackSink view, int detectors, boolean useMotion, boolean silhouette) {
        this.ctx = ctx;
        this.view = view;
        this.useMotion = useMotion;
        this.sil = silhouette ? new Silhouette() : null;
        workers = new Worker[detectors];
    }

    @SuppressLint("MissingPermission")  // checked by TrackerActivity
    void start() {
        camThread = new HandlerThread("camera");
        camThread.start();
        camBg = new Handler(camThread.getLooper());
        for (int i = 0; i < workers.length; i++) workers[i] = new Worker(i);

        reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(this::onFrame, camBg);
        CameraManager cm = ctx.getSystemService(CameraManager.class);
        try {
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) { view.setStatus("NO CAMERA - PLUG IN WEBCAM"); return; }
            view.setStatus("OPENING CAMERA " + ids[0]);
            cm.openCamera(ids[0], new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { cam = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close(); cam = null; view.setStatus("CAMERA DISCONNECTED");
                }
                @Override public void onError(CameraDevice d, int err) {
                    d.close(); cam = null; view.setStatus("CAMERA ERROR " + err);
                }
            }, camBg);
        } catch (Exception e) {
            Log.e(TrackerActivity.TAG, "open failed", e);
            view.setStatus("CAMERA OPEN FAILED");
        }
    }

    @SuppressWarnings("deprecation")  // list-of-surfaces API is fine on API 31
    private void startSession() {
        try {
            cam.createCaptureSession(Collections.singletonList(reader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        try {
                            CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            b.addTarget(reader.getSurface());
                            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));
                            s.setRepeatingRequest(b.build(), null, camBg);
                            statsStart = SystemClock.elapsedRealtime();
                            view.setStatus(null);
                        } catch (Exception e) {
                            Log.e(TrackerActivity.TAG, "request failed", e);
                            view.setStatus("CAMERA REQUEST FAILED");
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        view.setStatus("CAMERA SESSION FAILED");
                    }
                }, camBg);
        } catch (Exception e) {
            Log.e(TrackerActivity.TAG, "session failed", e);
            view.setStatus("CAMERA SESSION FAILED");
        }
    }

    private void onFrame(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            camFrames++;
            if (sil != null) {
                Image.Plane y = img.getPlanes()[0];
                view.setBlocks(sil.process(y.getBuffer(), y.getRowStride(), y.getPixelStride()));
            } else {
                motion(img);
            }
            for (Worker w : workers) {
                if (w.busy || w.detector == null) continue;
                copy(img, w.frame);
                w.frameAt = img.getTimestamp();
                w.busy = true;
                w.bg.post(w::run);
                break;
            }
        } finally {
            img.close();
        }
    }

    /** Slide every tracked box toward the motion in and around it. */
    private void motion(Image img) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int rs = y.getRowStride(), ps = y.getPixelStride(), cw = W / GW, ch = H / GH;
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                int sum = 0;
                for (int dy = 0; dy < ch; dy++) {
                    int row = (gy * ch + dy) * rs;
                    for (int dx = 0; dx < cw; dx++) sum += buf.get(row + (gx * cw + dx) * ps) & 0xff;
                }
                grid[gy * GW + gx] = (byte) (sum / (cw * ch));
            }
        }
        long ts = img.getTimestamp();
        latestTs = ts;
        if (havePrev) {
            synchronized (this) {
                for (Track t : tracks) {
                    float l = t.l(), r = t.r(), pad = (r - l) * 0.25f;
                    int x0 = Math.max(0, (int) ((l - pad) * GW)), x1 = Math.min(GW, (int) ((r + pad) * GW) + 1);
                    int y0 = Math.max(0, (int) (t.base.t * GH)), y1 = Math.min(GH, (int) (t.base.b * GH) + 1);
                    int n = 0, sx = 0;
                    for (int gy = y0; gy < y1; gy++) {
                        for (int gx = x0; gx < x1; gx++) {
                            int i = gy * GW + gx;
                            if (Math.abs((grid[i] & 0xff) - (prevGrid[i] & 0xff)) > DIFF) { n++; sx += gx; }
                        }
                    }
                    if (useMotion && n >= MIN_CELLS) {
                        float cx = (sx / (float) n + 0.5f) / GW;
                        float step = Math.max(-MAX_STEP, Math.min(MAX_STEP, (cx - (l + r) / 2) * GAIN));
                        t.shift += step;
                        t.motionSum += Math.abs(step);
                        motionMoves++;
                    }
                    t.record(ts);
                    t.corr *= 0.8f;   // glide out detection corrections (~150 ms)
                }
                if (!tracks.isEmpty()) publish();
            }
        }
        System.arraycopy(grid, 0, prevGrid, 0, grid.length);
        havePrev = true;
    }

    private static void copy(Image img, Detector.Frame frame) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        int rs = p[0].getRowStride();
        for (int y = 0; y < H; y++) {
            yb.position(y * rs);
            yb.get(frame.y, y * W, W);
        }
        frame.u = grab(p[1].getBuffer(), frame.u);
        frame.v = grab(p[2].getBuffer(), frame.v);
        frame.cRowStride = p[1].getRowStride();
        frame.cPixStride = p[1].getPixelStride();
    }

    private static byte[] grab(ByteBuffer b, byte[] into) {
        b.rewind();
        if (into.length < b.remaining()) into = new byte[b.remaining()];
        b.get(into, 0, b.remaining());
        return into;
    }

    private void stats() {
        long now = SystemClock.elapsedRealtime();
        long span = now - statsStart;
        if (span < 2000) return;
        int runs = Math.max(1, detRuns);
        float detFps = detRuns * 1000f / span, ms = detNanos / 1e6f / runs;
        Log.d(TrackerActivity.TAG, String.format(
            "camera %.1f fps, %d detectors, %.1f results/s, %.0f ms each (prep %.0f, model %.0f), motion moves %.1f/s",
            camFrames * 1000f / span, workers.length, detFps, ms, prepNanos / 1e6f / runs, inferNanos / 1e6f / runs,
            motionMoves * 1000f / span));
        view.setStats(detFps, ms);
        camFrames = 0; detRuns = 0; detNanos = 0; prepNanos = 0; inferNanos = 0; motionMoves = 0;
        statsStart = now;
    }

    /** Match each detection to the nearest track (by centre, as of the
     *  detection's frame). Distance, not overlap: a fast mover's late box can
     *  miss the replayed track entirely, which used to spawn a twin. New
     *  tracks must be seen CONFIRM times before they're shown (kills one-off
     *  false positives); overlapping twins merge into the fresher one. */
    private void update(List<Detector.Person> people, long frameAt) {
        long now = SystemClock.elapsedRealtime();
        boolean[] used = new boolean[people.size()];
        boolean[] matched = new boolean[tracks.size()];
        // greedy: closest track/detection pairs first
        while (true) {
            int bt = -1, bp = -1;
            float bestD = Float.MAX_VALUE;
            for (int ti = 0; ti < tracks.size(); ti++) {
                if (matched[ti]) continue;
                Track t = tracks.get(ti);
                for (int pi = 0; pi < people.size(); pi++) {
                    if (used[pi]) continue;
                    float d = t.distanceAt(people.get(pi), frameAt, useMotion);
                    if (d < bestD) { bestD = d; bt = ti; bp = pi; }
                }
            }
            if (bt < 0 || bestD > Track.MATCH_GATE) break;
            matched[bt] = true;
            used[bp] = true;
            tracks.get(bt).see(people.get(bp), now, frameAt, useMotion);
        }
        for (int i = 0; i < people.size(); i++) {
            if (!used[i]) {
                Track t = new Track(nextId++);
                t.see(people.get(i), now, frameAt, useMotion);
                tracks.add(t);
            }
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (now - tracks.get(i).seenAt > Track.LOST_MS) tracks.remove(i);
        }
        mergeTwins();
        publish();
        StringBuilder sb = new StringBuilder("tracks:");
        for (Track t : tracks) {
            sb.append(String.format(" #%d%s x%.3f det %.3f v%.2f motion %.3f jump %.3f ratio %.2f/%.2f vote %s -> %s", t.id,
                t.hits >= Track.CONFIRM ? "" : "(new)", useMotion ? (t.l() + t.r()) / 2 : t.predict(latestTs),
                (t.base.l + t.base.r) / 2, t.fv,
                t.motionSum, t.jumpSum, t.base.ratio(), t.baseline, t.vote, t.armsOut ? "OUT" : "down"));
            t.motionSum = 0; t.jumpSum = 0;
        }
        Log.d(TrackerActivity.TAG, sb.toString());
    }

    private void mergeTwins() {
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track a = tracks.get(i);
            for (int j = 0; j < tracks.size(); j++) {
                Track b = tracks.get(j);
                if (a == b) continue;
                boolean aOlder = a.seenAt < b.seenAt || (a.seenAt == b.seenAt && a.id > b.id);
                if (aOlder && a.overlap(b) > 0.4f) {
                    // keep the established id if the fresher twin is still unconfirmed
                    if (b.hits < Track.CONFIRM && a.hits >= Track.CONFIRM) b.adopt(a);
                    tracks.remove(i);
                    break;
                }
            }
        }
    }

    private void publish() {
        List<TrackSink.Block> out = new ArrayList<>();
        for (Track t : tracks) if (t.hits >= Track.CONFIRM) out.add(t.block(latestTs, useMotion));
        view.setBlocks(out);
    }

    void stop() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        if (cam != null) cam.close();
        if (reader != null) reader.close();
        if (camThread != null) camThread.quitSafely();
        for (Worker w : workers) if (w != null) w.stop();
        session = null; cam = null; reader = null;
    }

    /** One person over time, with the arms-out call.
     *
     *  Arms out makes the person's box wider for the same height. A fixed
     *  width/height cut fails when only the upper body is in view (that box is
     *  wide even with arms down), so each track learns its own arms-down shape:
     *  baseline = 20th percentile of recent arms-down ratios. False "out"
     *  calls came from motion blur and edge clipping widening single boxes,
     *  so a detection only votes when the person is slow and clear of the
     *  side edges, and the state flips only after VOTES votes in a row. */
    static final class Track {
        static final long LOST_MS = 600;
        static final int CONFIRM = 3;
        static final float MATCH_GATE = 0.35f;   // centre distance, frame widths
        // play logs: SSD boxes often stop short of the fingertips, so real
        // arms-out reads 1.2-1.8x the arms-down shape; 1.55 missed most of it
        static final float OUT_FACTOR = 1.35f, IN_FACTOR = 1.2f;  // hysteresis
        static final float CLEAR_DOWN = 1.15f;    // only shapes below this teach the baseline
        static final float MIN_OUT_RATIO = 0.65f;
        static final int VOTES = 3;               // ~0.4 s at 7.5 detections/s
        static final float FAST = 0.5f;           // frame widths per second
        static final float EDGE = 0.02f;
        static final int MIN_SAMPLES = 5;

        final int id;
        Detector.Person base;       // latest detection, as of its own frame
        float baseShift;            // motion shift at that frame
        float shift;                // cumulative motion shift, frame fractions
        float corr;                 // display offset that hides a detection's jump, decays to 0
        float motionSum, jumpSum;   // |motion steps| and |detection jumps| since last log (debug)
        // alpha-beta filter on the centre (frame fractions, camera ns clock)
        // Velocity is the slope of a line through the last VEL_WINDOW_S of
        // detections, not a per-detection beta term: the two detectors often
        // finish ~35 ms apart and dividing a wobble by that dt spiked the
        // velocity, throwing the umbrella up to 330 px in a frame. A lone
        // detection far from the prediction is skipped unless the next agrees.
        static final float ALPHA = 0.5f, MAX_V = 1.2f;
        static final float MAX_LEAD_S = 0.3f, LEAD_GAIN = 0.7f;
        static final float VEL_WINDOW_S = 0.6f, OUTLIER = 0.18f;
        float fx, fv;
        long fAt;
        boolean fInit, pendingOutlier;
        private final long[] zAt = new long[16];
        private final float[] zs = new float[16];
        private int zN, zHead;
        long seenAt;
        int hits;
        // (camera timestamp, shift) for replaying late detections; ~2 s
        private final long[] histAt = new long[64];
        private final float[] histShift = new float[64];
        private int histN, histHead;
        float baseline = -1;
        boolean armsOut;
        String vote = "";           // why the last detection did or didn't vote (logged)
        private int streak;         // consecutive votes against the current state
        private final float[] ratios = new float[80];   // recent arms-down shapes, ~12 s
        private int ratioN, ratioHead, outSamples;
        private float prevCx = -1;
        private long prevAt;

        Track(int id) { this.id = id; }

        float l() { return base.l + shift - baseShift; }
        float r() { return base.r + shift - baseShift; }

        void record(long ts) {
            histAt[histHead] = ts;
            histShift[histHead] = shift;
            histHead = (histHead + 1) % histAt.length;
            if (histN < histAt.length) histN++;
        }

        /** shift as it was at camera time ts (the newest record not after it) */
        float shiftAt(long ts) {
            for (int k = 1; k <= histN; k++) {
                int i = (histHead - k + histAt.length) % histAt.length;
                if (histAt[i] <= ts) return histShift[i];
            }
            return histN > 0 ? histShift[(histHead - histN + histAt.length) % histAt.length] : shift;
        }

        /** least-squares slope of recent detection centres, frame widths/s */
        private float slope(long now) {
            double st = 0, sz = 0, stt = 0, stz = 0;
            int n = 0;
            long first = Long.MAX_VALUE;
            for (int k = 0; k < zN; k++) {
                if ((now - zAt[k]) / 1e9f > VEL_WINDOW_S) continue;
                double t = (zAt[k] - now) / 1e9;
                st += t; sz += zs[k]; stt += t * t; stz += t * zs[k];
                n++;
                first = Math.min(first, zAt[k]);
            }
            if (n < 3 || (now - first) / 1e9f < 0.25f) return 0;
            double den = n * stt - st * st;
            if (den < 1e-9) return 0;
            float v = (float) ((n * stz - st * sz) / den);
            return Math.max(-MAX_V, Math.min(MAX_V, v));
        }

        /** filtered centre predicted to camera time ts */
        float predict(long ts) {
            float lead = Math.max(0, Math.min(MAX_LEAD_S, (ts - fAt) / 1e9f));
            return Math.max(0, Math.min(1, fx + fv * lead * LEAD_GAIN));
        }

        void see(Detector.Person p, long now, long frameAt, boolean useMotion) {
            float z = (p.l + p.r) / 2;
            if (!fInit || (frameAt - fAt) / 1e9f > 0.6f) {   // first sighting / reacquired
                fx = z; fv = 0; fInit = true; zN = 0; pendingOutlier = false;
            } else {
                float dt = Math.max(0, (frameAt - fAt) / 1e9f);
                float pred = fx + fv * dt, res = z - pred;
                if (Math.abs(res) > OUTLIER && !pendingOutlier) {
                    pendingOutlier = true;   // wait for a second opinion
                    vote = "outlier";
                    return;
                }
                pendingOutlier = false;
                fx = pred + ALPHA * res;
                jumpSum += Math.abs(ALPHA * res);
            }
            fAt = frameAt;
            zAt[zHead] = frameAt; zs[zHead] = z;
            zHead = (zHead + 1) % zs.length;
            if (zN < zs.length) zN++;
            fv = slope(frameAt);
            float before = base == null ? Float.NaN : (l() + r()) / 2 + corr;
            base = p;
            baseShift = shiftAt(frameAt);
            if (useMotion && before == before) {   // NaN check: first sighting snaps
                corr = before - (l() + r()) / 2;
                jumpSum += Math.abs(corr);
            }
            seenAt = now;
            hits++;
            float r = p.ratio(), cx = (p.l + p.r) / 2;
            float speed = prevCx < 0 ? 0 : Math.abs(cx - prevCx) / Math.max(0.05f, (frameAt - prevAt) / 1e9f);
            prevCx = cx;
            prevAt = frameAt;
            if (speed > FAST) { vote = "fast"; return; }
            if (p.l < EDGE || p.r > 1 - EDGE) { vote = "edge"; return; }

            if (baseline < 0) baseline = r;
            boolean saysOut = ratioN >= MIN_SAMPLES
                && r > Math.max(MIN_OUT_RATIO, baseline * (armsOut ? IN_FACTOR : OUT_FACTOR));
            // learn only from clearly arms-down shapes (half-raised arms were
            // creeping the baseline up); anything else 1 in 8, so a lasting
            // posture change (sitting, stepping closer) is still picked up
            if (r < baseline * CLEAR_DOWN || ++outSamples % 8 == 0) learn(r);
            vote = saysOut ? "out" : "down";
            if (saysOut != armsOut) {
                if (++streak >= VOTES) { armsOut = saysOut; streak = 0; }
            } else {
                streak = 0;
            }
        }

        private void learn(float r) {
            ratios[ratioHead] = r;
            ratioHead = (ratioHead + 1) % ratios.length;
            if (ratioN < ratios.length) ratioN++;
            float[] sorted = java.util.Arrays.copyOf(ratios, ratioN);
            java.util.Arrays.sort(sorted);
            baseline = sorted[ratioN / 5];
        }

        /** centre distance to where this track was at the detection's frame */
        float distanceAt(Detector.Person p, long frameAt, boolean useMotion) {
            float cx = useMotion ? (base.l + base.r) / 2 + shiftAt(frameAt) - baseShift : predict(frameAt);
            float cy = (base.t + base.b) / 2;
            float dx = cx - (p.l + p.r) / 2, dy = (cy - (p.t + p.b) / 2) * 0.75f;  // 4:3 frame
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        /** overlap of the two boxes now, as a fraction of the smaller one */
        float overlap(Track o) {
            float il = Math.max(l(), o.l()), ir = Math.min(r(), o.r());
            float it = Math.max(base.t, o.base.t), ib = Math.min(base.b, o.base.b);
            float inter = Math.max(0, ir - il) * Math.max(0, ib - it);
            float a = (r() - l()) * (base.b - base.t), b = (o.r() - o.l()) * (o.base.b - o.base.t);
            return inter / Math.max(1e-6f, Math.min(a, b));
        }

        /** take over an established twin's identity and arms-down shape */
        void adopt(Track o) {
            hits = Math.max(hits, o.hits);
            baseline = o.baseline;
            armsOut = o.armsOut;
            System.arraycopy(o.ratios, 0, ratios, 0, ratios.length);
            ratioN = o.ratioN;
            ratioHead = o.ratioHead;
        }

        TrackSink.Block block(long ts, boolean useMotion) {
            float half = (base.r - base.l) / 2;
            float cx = useMotion ? (l() + r()) / 2 + corr : predict(ts);
            // mirrored so the block moves the way you do
            return new TrackSink.Block(id, 1f - cx - half, base.t, 1f - cx + half, base.b, armsOut);
        }
    }
}
