package com.compact;

import android.app.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.compact.util.Ui;
import com.compact.work.*;

public final class CompareActivity extends Activity {
  private Bitmap first, second;
  private VideoView video;
  private float zoom = 1, dx, dy;
  private ZoomPane left, right;
  private long id;
  private boolean showingOutput;
  private int position;
  private JobQueue.Job job;

  protected void onCreate(Bundle b) {
    super.onCreate(b);
    id = getIntent().getLongExtra("id", 0);
    try (JobQueue q = new JobQueue(this)) {
      job = q.get(id);
    }
    if (job == null || job.outputUri == null) {
      finish();
      return;
    }
    if (b != null) {
      position = b.getInt("position");
      showingOutput = b.getBoolean("output", true);
    } else showingOutput = true;
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    Ui.edge(this, root, 16);
    setContentView(root);
    TextView title = Ui.text(this, "Compare · " + job.item.name, 20, Ui.TEXT);
    Ui.add(root, title);
    Ui.add(
        root,
        Ui.text(
            this,
            Ui.size(job.item.size)
                + " → "
                + Ui.size(job.outputSize)
                + "  ·  SSIM "
                + Ui.score(job.score),
            14,
            Ui.MUTED));
    if (job.item.video) {
      video = new VideoView(this);
      root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
      MediaController controller = new MediaController(this);
      controller.setAnchorView(video);
      video.setMediaController(controller);
      Button toggle = Ui.button(this, "Switch to original video", true);
      toggle.setOnClickListener(
          v -> {
            position = video.getCurrentPosition();
            showingOutput = !showingOutput;
            toggle.setText(showingOutput ? "Switch to original video" : "Switch to compressed video");
            play();
          });
      Ui.add(root, toggle);
      play();
    } else {
      Ui.add(
          root,
          Ui.text(
              this,
              "Original (left) · Compressed (right)\n"
                  + "Pinch and drag either preview; double-tap to reset.",
              14,
              Ui.MUTED));
      LinearLayout pair = new LinearLayout(this);
      left = new ZoomPane();
      right = new ZoomPane();
      pair.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
      pair.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
      root.addView(pair, new LinearLayout.LayoutParams(-1, 0, 1));
      new Thread(
              () -> {
                try {
                  Bitmap a = decode(job.item.uri);
                  Bitmap bmap = decode(Uri.parse(job.outputUri));
                  runOnUiThread(
                      () -> {
                        if (isDestroyed()) {
                          a.recycle();
                          bmap.recycle();
                          return;
                        }
                        first = a;
                        second = bmap;
                        left.invalidate();
                        right.invalidate();
                      });
                } catch (Exception e) {
                  runOnUiThread(
                      () -> {
                        if (isDestroyed()) return;
                        new AlertDialog.Builder(this)
                            .setMessage(
                                "Preview unavailable. Restore the original from Recently replaced"
                                    + " if it is in Trash.")
                            .setPositiveButton("OK", (d, w) -> finish())
                            .show();
                      });
                }
              },
              "Compact-compare")
          .start();
    }
  }

  private Bitmap decode(Uri uri) throws Exception {
    return ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(getContentResolver(), uri),
        (d, info, src) -> {
          d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
          int w = info.getSize().getWidth(), h = info.getSize().getHeight();
          double s = Math.min(1, 2048d / Math.max(w, h));
          d.setTargetSize((int) (w * s), (int) (h * s));
        });
  }

  private void play() {
    video.stopPlayback();
    video.setVideoURI(showingOutput ? Uri.parse(job.outputUri) : job.item.uri);
    video.setOnPreparedListener(
        p -> {
          p.seekTo((long) position, android.media.MediaPlayer.SEEK_CLOSEST);
          video.start();
        });
    video.setOnErrorListener(
        (p, w, e) -> {
          new AlertDialog.Builder(this)
              .setMessage("This video is unavailable. The original may be in system Trash.")
              .setPositiveButton("OK", null)
              .show();
          return true;
        });
    setTitle(showingOutput ? "Compressed" : "Original");
  }

  protected void onSaveInstanceState(Bundle b) {
    if (video != null) position = video.getCurrentPosition();
    b.putInt("position", position);
    b.putBoolean("output", showingOutput);
    super.onSaveInstanceState(b);
  }

  protected void onPause() {
    if (video != null) {
      position = video.getCurrentPosition();
      video.pause();
    }
    super.onPause();
  }

  protected void onDestroy() {
    if (video != null) video.stopPlayback();
    if (first != null) first.recycle();
    if (second != null) second.recycle();
    super.onDestroy();
  }

  private final class ZoomPane extends View {
    final Paint paint = new Paint(3);
    float lastX, lastY;
    final ScaleGestureDetector scale;
    final GestureDetector taps;

    ZoomPane() {
      super(CompareActivity.this);
      scale =
          new ScaleGestureDetector(
              CompareActivity.this,
              new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScale(ScaleGestureDetector d) {
                  zoom = Math.max(1, Math.min(12, zoom * d.getScaleFactor()));
                  redraw();
                  return true;
                }
              });
      taps =
          new GestureDetector(
              CompareActivity.this,
              new GestureDetector.SimpleOnGestureListener() {
                public boolean onDown(android.view.MotionEvent e) {
                  return true;
                }

                public boolean onDoubleTap(android.view.MotionEvent e) {
                  zoom = 1;
                  dx = dy = 0;
                  redraw();
                  return true;
                }
              });
    }

    protected void onDraw(Canvas c) {
      Bitmap b = this == left ? first : second;
      if (b == null) return;
      float fit = Math.min(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
      c.save();
      c.translate(getWidth() / 2f + dx * getWidth(), getHeight() / 2f + dy * getHeight());
      c.scale(fit * zoom, fit * zoom);
      c.drawBitmap(b, -b.getWidth() / 2f, -b.getHeight() / 2f, paint);
      c.restore();
    }

    public boolean onTouchEvent(android.view.MotionEvent e) {
      scale.onTouchEvent(e);
      taps.onTouchEvent(e);
      if (e.getActionMasked() == 0) {
        lastX = e.getX();
        lastY = e.getY();
      } else if (e.getActionMasked() == 2) {
        if (!scale.isInProgress()) {
          dx += (e.getX() - lastX) / getWidth();
          dy += (e.getY() - lastY) / getHeight();
          redraw();
        }
        lastX = e.getX();
        lastY = e.getY();
      }
      return true;
    }

    void redraw() {
      left.invalidate();
      right.invalidate();
    }
  }
}
