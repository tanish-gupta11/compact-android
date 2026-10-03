package com.compact.quality;

import android.graphics.*;
import java.io.*;

public final class PhotoQuality {
  public static final class Score {
    public double mean = 0, min = 1, psnr;
    public long windows;
    private double squaredError;
    private long pixels;
    public boolean identical = true;
  }

  public static Score compare(File source, File output, boolean exact) throws IOException {
    BitmapRegionDecoder a = null, b = null;
    try {
      a = BitmapRegionDecoder.newInstance(source.getPath(), false);
      b = BitmapRegionDecoder.newInstance(output.getPath(), false);
      if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
        throw new IOException("Photo dimensions changed");
      int w = a.getWidth(), h = a.getHeight();
      if (w < 8 || h < 8) throw new IOException("Photo too small");
      Score s = new Score();
      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
      for (int y = 0; y < h; y += 1024)
        for (int x = 0; x < w; x += 1024) {
          Rect rect = new Rect(x, y, Math.min(w, x + 1028), Math.min(h, y + 1028));
          Bitmap aa = a.decodeRegion(rect, opts), bb = b.decodeRegion(rect, opts);
          if (aa == null || bb == null) throw new IOException("Photo tile decode failed");
          try {
            int tw = aa.getWidth(), th = aa.getHeight();
            int[] ap = new int[tw * th], bp = new int[ap.length];
            aa.getPixels(ap, 0, tw, 0, 0, tw, th);
            bb.getPixels(bp, 0, tw, 0, 0, tw, th);
            if (!java.util.Arrays.equals(ap, bp)) s.identical = false;
            if (tw >= 8 && th >= 8) {
              Ssim.Result q = Ssim.measure(ap, bp, tw, th);
              s.mean += q.ssim * q.windows;
              s.windows += q.windows;
              s.min = Math.min(s.min, q.ssim);
              s.squaredError += q.squaredError;
              s.pixels += q.pixels;
            }
          } finally {
            aa.recycle();
            bb.recycle();
          }
        }
      if (s.windows == 0) throw new IOException("No quality samples");
      s.mean /= s.windows;
      s.psnr = Ssim.psnr(s.squaredError, s.pixels);
      if (exact && !s.identical) throw new IOException("Lossless pixel verification failed");
      return s;
    } finally {
      if (a != null) a.recycle();
      if (b != null) b.recycle();
    }
  }
}
