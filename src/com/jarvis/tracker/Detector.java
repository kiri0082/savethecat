package com.jarvis.tracker;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SSD MobileNet v1 (quantized, COCO) on the CPU, people only. EfficientDet-
 *  Lite0 also works (same outputs) but took ~430 ms a frame on this box vs.
 *  the game's need for snappy tracking. The 320x240 frame is scaled and
 *  letterboxed into the model's square input; returned boxes are fractions of
 *  the camera frame. */
final class Detector {

    static final String MODEL = "ssd_mobilenet_v1.tflite";
    static final int PERSON = 0;          // COCO class id in this model
    static final float MIN_SCORE = 0.5f;

    static final class Person {
        final float l, t, r, b, score;     // fractions of the camera frame
        Person(float l, float t, float r, float b, float score) {
            this.l = l; this.t = t; this.r = r; this.b = b; this.score = score;
        }
        /** width / height in real pixels (the frame is 4:3) */
        float ratio() { return ((r - l) * 4f) / Math.max(1e-3f, (b - t) * 3f); }
    }

    private final Interpreter tfl;
    private final int size;                // model input side
    private final ByteBuffer input;
    private final int frameRows;           // input rows the camera frame fills
    private final int padTop;              // letterbox rows above the frame
    private int[] srcX, srcY;              // nearest-neighbour resample maps
    private int boxesIdx = -1, classesIdx = -1, scoresIdx = -1, countIdx = -1;
    private final float[][][] boxes;
    private final float[][] classes, scores;
    private final float[] count = new float[1];

    Detector(Context ctx, int threads) throws Exception {
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(threads);
        Log.d(TrackerActivity.TAG, "model threads " + threads);
        tfl = new Interpreter(loadModel(ctx), o);
        Tensor in = tfl.getInputTensor(0);
        size = in.shape()[1];
        Log.d(TrackerActivity.TAG, "model in " + java.util.Arrays.toString(in.shape()) + " " + in.dataType());
        if (in.dataType() != DataType.UINT8) throw new IllegalStateException("expected uint8 input");
        input = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder());
        frameRows = size * CameraTracker.H / CameraTracker.W;
        padTop = (size - frameRows) / 2;
        srcX = new int[size];
        for (int i = 0; i < size; i++) srcX[i] = i * CameraTracker.W / size;
        srcY = new int[frameRows];
        for (int i = 0; i < frameRows; i++) srcY[i] = i * CameraTracker.H / frameRows;

        // output order differs between exports; tell them apart by shape/name
        int n = 0;
        for (int i = 0; i < tfl.getOutputTensorCount(); i++) {
            Tensor t = tfl.getOutputTensor(i);
            int[] s = t.shape();
            Log.d(TrackerActivity.TAG, "model out " + i + " " + t.name() + " " + java.util.Arrays.toString(s));
            if (s.length == 3) { boxesIdx = i; n = s[1]; }
            else if (s.length == 1) countIdx = i;
        }
        for (int i = 0; i < tfl.getOutputTensorCount(); i++) {
            if (i == boxesIdx || i == countIdx) continue;
            // PostProcess emits classes before scores
            if (classesIdx < 0) classesIdx = i; else scoresIdx = i;
        }
        boxes = new float[1][n][4];
        classes = new float[1][n];
        scores = new float[1][n];
    }

    long prepNanos, inferNanos;  // read + reset by CameraTracker's stats

    List<Person> detect(Frame f) {
        long t0 = System.nanoTime();
        fillInput(f);
        long t1 = System.nanoTime();
        prepNanos += t1 - t0;
        Map<Integer, Object> out = new HashMap<>();
        out.put(boxesIdx, boxes);
        out.put(classesIdx, classes);
        out.put(scoresIdx, scores);
        out.put(countIdx, count);
        tfl.runForMultipleInputsOutputs(new Object[] {input}, out);
        inferNanos += System.nanoTime() - t1;
        if (!verified) verifyOrder();

        List<Person> people = new ArrayList<>();
        int n = Math.min((int) count[0], scores[0].length);
        float fh = frameRows;
        for (int i = 0; i < n; i++) {
            if ((int) classes[0][i] != PERSON || scores[0][i] < MIN_SCORE) continue;
            float[] bx = boxes[0][i];          // ymin, xmin, ymax, xmax of the 320x320 input
            float t = clamp((bx[0] * size - padTop) / fh);
            float b = clamp((bx[2] * size - padTop) / fh);
            people.add(new Person(clamp(bx[1]), t, clamp(bx[3]), b, scores[0][i]));
        }
        return people;
    }

    private boolean verified;

    // the index-order guess is right for MediaPipe exports; if "classes" holds
    // fractions it was really the scores - swap once and say so
    private void verifyOrder() {
        int n = Math.min((int) count[0], classes[0].length);
        for (int i = 0; i < n; i++) {
            float c = classes[0][i];
            if (c != (int) c) {
                int t = classesIdx; classesIdx = scoresIdx; scoresIdx = t;
                Log.d(TrackerActivity.TAG, "model outputs: classes/scores swapped");
                break;
            }
        }
        if (n > 0) verified = true;
    }

    private byte[] rgb;   // one input image, bulk-copied into the direct buffer

    private void fillInput(Frame f) {
        if (rgb == null) rgb = new byte[size * size * 3];   // pad rows stay 0
        byte[] o = rgb, fy = f.y, fu = f.u, fv = f.v;
        int w = CameraTracker.W, cps = f.cPixStride;
        int k = padTop * size * 3;
        for (int iy = 0; iy < frameRows; iy++) {
            int y = srcY[iy];
            int yRow = y * w;
            int cRow = (y >> 1) * f.cRowStride;
            for (int ix = 0; ix < size; ix++) {
                int x = srcX[ix];
                int Y = fy[yRow + x] & 0xff;
                int ci = cRow + (x >> 1) * cps;
                int U = (fu[ci] & 0xff) - 128, V = (fv[ci] & 0xff) - 128;
                o[k++] = sat(Y + ((359 * V) >> 8));
                o[k++] = sat(Y - ((88 * U + 183 * V) >> 8));
                o[k++] = sat(Y + ((454 * U) >> 8));
            }
        }
        input.rewind();
        input.put(o);
        input.rewind();
    }

    void close() { tfl.close(); }

    private static byte sat(int v) { return (byte) (v < 0 ? 0 : v > 255 ? 255 : v); }
    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static ByteBuffer loadModel(Context ctx) throws Exception {
        try (InputStream in = ctx.getAssets().open(MODEL)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            byte[] bytes = buf.toByteArray();
            ByteBuffer bb = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            bb.put(bytes).rewind();
            return bb;
        }
    }

    /** One camera frame copied off the ImageReader so the camera can move on. */
    static final class Frame {
        final byte[] y = new byte[CameraTracker.W * CameraTracker.H];
        byte[] u = new byte[0], v = new byte[0];
        int cRowStride, cPixStride;
    }
}
