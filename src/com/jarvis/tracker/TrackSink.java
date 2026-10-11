package com.jarvis.tracker;

/** Where CameraTracker sends tracked people: the cat game, or the plain
 *  block view kept for debugging (am start ... --es view blocks). */
interface TrackSink {

    /** One tracked person: box as mirrored fractions of the camera frame. */
    final class Block {
        final int id;
        final float l, t, r, b;
        final boolean armsOut;
        Block(int id, float l, float t, float r, float b, boolean armsOut) {
            this.id = id; this.l = l; this.t = t; this.r = r; this.b = b; this.armsOut = armsOut;
        }
    }

    void setBlocks(java.util.List<Block> blocks);
    void setStatus(String status);
    void setStats(float detectionsPerSec, float msEach);
}
