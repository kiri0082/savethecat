package com.jarvis.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/** Full-screen cat game (or debug block view): the camera runs only while
 *  this is on screen, and only people's positions ever reach the display -
 *  never the video. */
public class TrackerActivity extends Activity {

    static final String TAG = "JarvisTrack";
    private TrackSink view;
    private CameraTracker tracker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // the game by default; the plain block view stays for tuning the tracker
        View v = "blocks".equals(getIntent().getStringExtra("view"))
            ? new BlockView(this) : new GameView(this);
        view = (TrackSink) v;
        setContentView(v);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            view.setStatus("CAMERA PERMISSION NEEDED");
            requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
            return;
        }
        startCamera();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else view.setStatus("CAMERA PERMISSION DENIED");
    }

    private void startCamera() {
        if (tracker != null) return;
        // silhouette tracking by default; the detector-driven tracker stays for
        // comparison: am start ... --es track detector [--ei detectors N --ez motion true]
        boolean detectorMode = "detector".equals(getIntent().getStringExtra("track"));
        tracker = new CameraTracker(this, view, getIntent().getIntExtra("detectors", detectorMode ? 2 : 1),
            getIntent().getBooleanExtra("motion", false), !detectorMode);
        tracker.start();
    }

    @Override
    protected void onPause() {
        // camera off the moment we leave the screen - off the UI thread, since
        // closing a camera that was unplugged mid-game can block in the HAL
        if (tracker != null) {
            final CameraTracker t = tracker;
            tracker = null;
            new Thread(() -> { t.stop(); Log.d(TAG, "camera released"); }).start();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Never linger behind IPTV: after the camera was unplugged mid-game the
        // process sat cached holding the camera op (green privacy dot) for an
        // hour. Exiting is the only release that can't get stuck, and the game
        // relearns the room on every launch anyway.
        finishAndRemoveTask();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 1500);
    }

    private void hideBars() {
        ((View) view).setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
