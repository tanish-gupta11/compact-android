package com.compact;

import android.app.*;
import android.graphics.*;
import android.media.*;
import android.media.ExifInterface;
import android.os.*;
import androidx.heifwriter.HeifWriter;
import com.compact.photo.*;
import com.compact.quality.*;
import com.compact.video.*;
import java.io.*;

/**
 * Engine diagnostics on CompactTest_* fixtures copied into the QA cache (diag/ folder). Prints sizes,
 * SSIM and every failure so thresholds and encoder behaviour can be measured on a real device.
 */
public final class QaDiag extends Instrumentation {
  private final StringBuilder log = new StringBuilder();
  private File transcript;

  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  private void say(String s) {
    android.util.Log.i("CompactDiag", s);
    log.append(s).append('\n');
    if (transcript != null) {
      try (FileWriter writer = new FileWriter(transcript, true)) {
        writer.write(s);
        writer.write('\n');
      } catch (IOException error) {
        android.util.Log.e("CompactDiag", "Could not save QA log", error);
      }
    }
  }

  public void onStart() {
    File dir = new File(getTargetContext().getCacheDir(), "diag");
    File out = new File(dir, "out");
    out.mkdirs();
    transcript = new File(out, "CompactTest_results.txt");
    if (transcript.exists()) transcript.delete();
    File[] files = dir.listFiles();
    try {
      encoders();
      if (files != null)
        for (File f : files) {
          if (!f.isFile() || !f.getName().startsWith("CompactTest_")) continue;
          try {
            if (f.getName().endsWith(".jpg")) photo(f, out);
            else video(f, out);
          } catch (Throwable e) {
            say(f.getName() + " ERROR " + e);
          }
        }
    } catch (Throwable e) {
      say("FATAL " + e);
    }
    Bundle r = new Bundle();
    r.putString("log", log.toString());
    finish(Activity.RESULT_OK, r);
  }

  private void encoders() {
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
      if (!info.isEncoder()) continue;
      for (String t : info.getSupportedTypes()) {
        if (!t.equals("video/hevc") && !t.equals("image/vnd.android.heic")) continue;
        MediaCodecInfo.CodecCapabilities c = info.getCapabilitiesForType(t);
        MediaCodecInfo.VideoCapabilities v = c.getVideoCapabilities();
        MediaCodecInfo.EncoderCapabilities e = c.getEncoderCapabilities();
        say("ENC " + info.getName() + " " + t + " hw=" + info.isHardwareAccelerated()
            + " w=" + (v == null ? "-" : v.getSupportedWidths() + " h=" + v.getSupportedHeights()
            + " br=" + v.getBitrateRange() + " 1080p60=" + v.areSizeAndRateSupported(1920, 1080, 60)
            + " 4k30=" + v.areSizeAndRateSupported(3840, 2160, 30))
            + " CQ=" + e.isBitrateModeSupported(0) + " VBR=" + e.isBitrateModeSupported(1)
            + " q=" + e.getQualityRange());
      }
    }
  }

  private void photo(File f, File out) throws Exception {
    if (f.getName().contains("photo2") || f.getName().contains("photo4") || f.getName().contains("photo1")) {
      BitmapFactory.Options d = new BitmapFactory.Options();
      d.inPreferredConfig = Bitmap.Config.ARGB_8888;
      Bitmap full = BitmapFactory.decodeFile(f.getPath(), d);
      java.lang.reflect.Method wj = PhotoEngine.class.getDeclaredMethod("writeJpeg", File.class, File.class, Bitmap.class, int.class);
      wj.setAccessible(true);
      for (int q : new int[] {85, 90, 94}) {
        File j = new File(out, f.getName() + ".j" + q + ".jpg");
        try {
          wj.invoke(null, f, j, full, q);
          String ex = "ok";
          try { ExifCopier.verify(f, j); } catch (Exception e) { ex = e.getMessage(); }
          PhotoQuality.Score sc = PhotoQuality.compare(f, j, false);
          say(String.format("  jpeg q%d %d (%.0f%%) ssim=%.4f min=%.4f psnr=%.1f exif=%s", q, j.length(),
              100.0 * j.length() / f.length(), sc.mean, sc.min, sc.psnr, ex));
        } catch (Throwable e) {
          say("  jpeg q" + q + " ERROR " + (e.getCause() != null ? e.getCause() : e));
        }
      }
      full.recycle();
    }
    File dir = new File(out, f.getName() + ".engine");
    dir.mkdirs();
    long t0 = SystemClock.elapsedRealtime();
    try {
      PhotoEngine.Result r = PhotoEngine.compress(f, dir, 0, true, () -> {});
      say(String.format("%s %d -> %s %d (%.0f%%) ssim=%.4f min=%.4f psnr=%.1f %dms", f.getName(), f.length(),
          r.mime, r.file.length(), 100.0 * r.file.length() / f.length(), r.score, r.min, r.psnr,
          SystemClock.elapsedRealtime() - t0));
    } catch (Throwable e) {
      say(f.getName() + " ENGINE FAILED " + e + " " + (SystemClock.elapsedRealtime() - t0) + "ms");
    }
    // How this device treats a HEIC whose rotation is stored natively.
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inSampleSize = 4;
    Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
    byte[] exif = ExifCopier.exif(ExifCopier.segments(com.compact.util.Files.read(f)));
    int orientation = ExifCopier.orientation(exif);
    File h = new File(out, f.getName() + ".rot.heic");
    java.lang.reflect.Method w = PhotoEngine.class.getDeclaredMethod("writeHeic", File.class, Bitmap.class, int.class, byte[].class, int.class);
    w.setAccessible(true);
    w.invoke(null, h, bm, 90, exif, orientation);
    ExifInterface ei = new ExifInterface(h.getPath());
    BitmapFactory.Options b = new BitmapFactory.Options();
    b.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(h.getPath(), b);
    Bitmap dec = ImageDecoder.decodeBitmap(ImageDecoder.createSource(h));
    BitmapRegionDecoder rd = BitmapRegionDecoder.newInstance(h.getPath(), false);
    String verify = "ok";
    try {
      ExifCopier.verify(f, h);
    } catch (Exception e) {
      verify = e.getMessage();
    }
    say(String.format("  heic-rotation srcOrient=%d src=%dx%d exifOrient=%s factory=%dx%d imageDecoder=%dx%d region=%dx%d verify=%s",
        orientation, bm.getWidth(), bm.getHeight(), ei.getAttribute(ExifInterface.TAG_ORIENTATION), b.outWidth, b.outHeight,
        dec.getWidth(), dec.getHeight(), rd.getWidth(), rd.getHeight(), verify));
    bm.recycle();
  }

  private void video(File f, File out) throws Exception {
    if (f.getName().contains("_max")) {
      File engineDir = new File(out, f.getName() + ".engine");
      engineDir.mkdirs();
      long start = SystemClock.elapsedRealtime();
      try {
        VideoTranscoder.Result result = VideoTranscoder.compress(f, engineDir, 2,
            new VideoTranscoder.Control() {
              public void check() {}
              public void progress(double fraction) {}
            });
        say(String.format(java.util.Locale.US, "  ENGINE Max %.0f%% mean=%.4f %ds",
            100.0 * result.file.length() / f.length(), result.score,
            (SystemClock.elapsedRealtime() - start) / 1000));
      } catch (Exception error) {
        say("  ENGINE Max kept original: " + error.getMessage() + " "
            + (SystemClock.elapsedRealtime() - start) / 1000 + "s");
      }
      return;
    }
    VideoProbe p = VideoProbe.read(f);
    say(f.getName() + " " + p.width + "x" + p.height + " rot=" + p.rotation + " fps=" + p.fps + " br=" + p.bitrate);
    java.lang.reflect.Method enc = VideoTranscoder.class.getDeclaredMethod("encode", File.class, File.class,
        VideoProbe.class, int.class, boolean.class, int.class, VideoTranscoder.Control.class);
    enc.setAccessible(true);
    VideoTranscoder.Control none = new VideoTranscoder.Control() {
      public void check() {}

      public void progress(double x) {}
    };
    for (double bpp : new double[] {.10, .14, .20, .27}) {
      File o = new File(out, f.getName() + ".ladder." + bpp + ".mp4");
      long rate = Math.round(p.width * (double) p.height * p.fps * bpp);
      long t0 = SystemClock.elapsedRealtime();
      if (!o.isFile()) enc.invoke(null, f, o, p, (int) rate, false, 0, none);
      long encMs = SystemClock.elapsedRealtime() - t0;
      MediaMetadataRetriever x = new MediaMetadataRetriever(), y = new MediaMetadataRetriever();
      x.setDataSource(f.getPath());
      y.setDataSource(o.getPath());
      int w = p.width, h = p.height;
      if (p.rotation == 90 || p.rotation == 270) { w = p.height; h = p.width; }
      double sc = Math.min(1, 1920d / Math.max(w, h));
      w = (int) (w * sc); h = (int) (h * sc);
      StringBuilder per = new StringBuilder();
      double sum = 0, worst = 1, se = 0; long px = 0;
      for (double t : new double[] {.1, .3, .5, .7, .9}) {
        long at = (long) (p.duration * t);
        Bitmap aa = x.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST, w, h);
        Bitmap bb = y.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST, w, h);
        int ww = aa.getWidth(), hh = aa.getHeight();
        int[] ap = new int[ww * hh], bp = new int[ap.length];
        aa.getPixels(ap, 0, ww, 0, 0, ww, hh);
        bb.getPixels(bp, 0, ww, 0, 0, ww, hh);
        Ssim.Result r = Ssim.measure(ap, bp, ww, hh);
        per.append(String.format(" %.3f/%.1f", r.ssim, r.psnr()));
        sum += r.ssim; worst = Math.min(worst, r.ssim); se += r.squaredError; px += r.pixels;
        if (t == .5) {
          try (FileOutputStream os = new FileOutputStream(o.getPath() + ".mid.out.png")) { bb.compress(Bitmap.CompressFormat.PNG, 100, os); }
          if (bpp == .10) try (FileOutputStream os = new FileOutputStream(new File(out, "mid.src.png"))) { aa.compress(Bitmap.CompressFormat.PNG, 100, os); }
        }
        aa.recycle(); bb.recycle();
      }
      x.release(); y.release();
      say(String.format("  bpp %.2f %.0f%% enc=%ds mean=%.3f worst=%.3f psnr=%.1f |%s", bpp, 100.0 * o.length() / f.length(),
          encMs / 1000, sum / 5, worst, Ssim.psnr(se, px), per));
    }
    File engineDir = new File(out, f.getName() + ".engine");
    engineDir.mkdirs();
    long start = SystemClock.elapsedRealtime();
    try {
      VideoTranscoder.Result result = VideoTranscoder.compress(f, engineDir, 0, none);
      say(String.format(java.util.Locale.US, "  ENGINE Smart %.0f%% mean=%.4f %ds",
          100.0 * result.file.length() / f.length(), result.score,
          (SystemClock.elapsedRealtime() - start) / 1000));
    } catch (Exception error) {
      say("  ENGINE Smart kept original: " + error.getMessage() + " "
          + (SystemClock.elapsedRealtime() - start) / 1000 + "s");
    }
  }

  private static double luma(int v) {
    return .299 * ((v >> 16) & 255) + .587 * ((v >> 8) & 255) + .114 * (v & 255);
  }

  private String frames(File a, File b, VideoProbe p, int cap) throws Exception {
    MediaMetadataRetriever x = new MediaMetadataRetriever(), y = new MediaMetadataRetriever();
    StringBuilder s = new StringBuilder();
    try {
      x.setDataSource(a.getPath());
      y.setDataSource(b.getPath());
      int w = p.width, h = p.height;
      if (p.rotation == 90 || p.rotation == 270) {
        w = p.height;
        h = p.width;
      }
      double scale = Math.min(1, (double) cap / Math.max(w, h));
      w = (int) (w * scale);
      h = (int) (h * scale);
      for (double t : new double[] {.1, .5, .9}) {
        Bitmap aa = x.getScaledFrameAtTime((long) (p.duration * t), MediaMetadataRetriever.OPTION_CLOSEST, w, h);
        Bitmap bb = y.getScaledFrameAtTime((long) (p.duration * t), MediaMetadataRetriever.OPTION_CLOSEST, w, h);
        int ww = aa.getWidth(), hh = aa.getHeight();
        if (bb.getWidth() != ww || bb.getHeight() != hh) {
          s.append("dims ").append(ww).append('x').append(hh).append('/').append(bb.getWidth()).append('x').append(bb.getHeight()).append(' ');
          continue;
        }
        int[] ap = new int[ww * hh], bp = new int[ap.length];
        aa.getPixels(ap, 0, ww, 0, 0, ww, hh);
        bb.getPixels(bp, 0, ww, 0, 0, ww, hh);
        s.append(String.format("%.4f@%dx%d ", Ssim.compare(ap, bp, ww, hh), ww, hh));
        if (t == .5) {
          try (FileOutputStream o = new FileOutputStream(b.getPath() + "." + cap + ".src.png")) { aa.compress(Bitmap.CompressFormat.PNG, 100, o); }
          try (FileOutputStream o = new FileOutputStream(b.getPath() + "." + cap + ".out.png")) { bb.compress(Bitmap.CompressFormat.PNG, 100, o); }
        }
      }
    } finally {
      x.release();
      y.release();
    }
    return s.toString().trim();
  }
}
