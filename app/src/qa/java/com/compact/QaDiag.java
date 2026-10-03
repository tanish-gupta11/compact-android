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

  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  private void say(String s) {
    android.util.Log.i("CompactDiag", s);
    log.append(s).append('\n');
  }

  public void onStart() {
    File dir = new File(getTargetContext().getCacheDir(), "diag");
    File out = new File(dir, "out");
    out.mkdirs();
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
    File dir = new File(out, f.getName() + ".engine");
    dir.mkdirs();
    long t0 = SystemClock.elapsedRealtime();
    try {
      VideoTranscoder.Result r = VideoTranscoder.compress(f, dir, 0, new VideoTranscoder.Control() {
        public void check() {}

        public void progress(double fraction) {}
      });
      say(String.format("%s %d -> %d (%.0f%%) ssim=%.4f %dms %s", f.getName(), f.length(), r.file.length(),
          100.0 * r.file.length() / f.length(), r.score, SystemClock.elapsedRealtime() - t0, r.file.getName()));
    } catch (Throwable e) {
      say(f.getName() + " ENGINE FAILED " + e + " " + (SystemClock.elapsedRealtime() - t0) + "ms");
    }
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
