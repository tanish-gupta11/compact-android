package com.compact.photo;

import android.graphics.*;
import androidx.heifwriter.HeifWriter;
import com.compact.quality.PhotoQuality;
import com.compact.quality.QualityGate;
import com.compact.util.Files;
import java.io.*;
import java.util.*;

/**
 * Smart/Max: HEIC (when this phone's encoder honours quality), else JPEG, each searched for the
 * smallest file that passes {@link QualityGate}. If neither saves 15%, falls back to Lossless, which
 * only repacks the JPEG and keeps every pixel identical.
 */
public final class PhotoEngine {
  public interface Control {
    void check() throws Exception;
  }

  public static final class Result {
    public File file;
    public String mime;
    public double score, min, psnr;
  }

  /** Set once an encoder is seen ignoring the quality setting (identical output across qualities). */
  private static volatile boolean heicUnreliable;

  public static Result compress(File source, File dir, int mode, boolean heic, Control control)
      throws Exception {
    byte[] original = Files.read(source);
    List<byte[]> segments = ExifCopier.segments(original);
    if (mode == 1) return lossless(original, source, dir, control);
    Result best = null;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(source.getPath(), options);
    if (options.outWidth <= 0 || options.outHeight <= 0)
      throw new IOException("Photo cannot be decoded");
    boolean fits =
        (long) options.outWidth * options.outHeight * 8 <= Runtime.getRuntime().maxMemory() * .65;
    if (fits) {
      options.inJustDecodeBounds = false;
      options.inPreferredConfig = Bitmap.Config.ARGB_8888;
      Bitmap bitmap = BitmapFactory.decodeFile(source.getPath(), options);
      if (bitmap == null) throw new IOException("Photo decode failed");
      try {
        if (bitmap.getColorSpace() == null || bitmap.getColorSpace().isSrgb()) {
          byte[] exif = ExifCopier.exif(segments);
          int orientation = ExifCopier.orientation(exif);
          boolean heicOk = heic && !heicUnreliable && ExifCopier.heicSafe(segments)
              && (orientation == 1 || orientation == 3 || orientation == 6 || orientation == 8);
          if (heicOk) best = search(source, dir, mode, true, bitmap, exif, orientation, control);
          if (best == null || best.file.length() > source.length() * .85) {
            Result jpeg = search(source, dir, mode, false, bitmap, exif, orientation, control);
            best = smaller(best, jpeg);
          }
        }
      } finally {
        bitmap.recycle();
      }
    }
    if (best != null && best.file.length() <= source.length() * .85) return best;
    if (best != null) Files.discard(best.file);
    try {
      return lossless(original, source, dir, control);
    } catch (IOException e) {
      throw new IOException(
          fits
              ? "Already compact: a smaller copy would change visible quality"
              : "Too large for this phone's memory, and lossless repacking saves under 2%");
    }
  }

  private static Result smaller(Result a, Result b) {
    if (a == null) return b;
    if (b == null) return a;
    Result keep = a.file.length() <= b.file.length() ? a : b;
    Files.discard((keep == a ? b : a).file);
    return keep;
  }

  /** Quality search: start mid, step down while passing, up while failing; keep the smallest pass. */
  private static Result search(File source, File dir, int mode, boolean heic, Bitmap bitmap,
      byte[] exif, int orientation, Control control) throws Exception {
    int[] plan = heic ? (mode == 2 ? new int[] {75, 65, 85} : new int[] {85, 78, 92})
        : (mode == 2 ? new int[] {88, 82, 92} : new int[] {93, 90, 95});
    Result best = null;
    long previousSize = -1;
    int step = 0, quality = plan[0];
    while (step < 3) {
      control.check();
      File output = new File(dir, "candidate" + step + (heic ? ".heic" : ".jpg"));
      Result r;
      try {
        if (heic) writeHeic(output, bitmap, quality, exif, orientation);
        else writeJpeg(source, output, bitmap, quality);
        ExifCopier.verify(source, output);
        PhotoQuality.Score q = PhotoQuality.compare(source, output, false);
        r = new Result();
        r.file = output;
        r.mime = heic ? "image/heic" : "image/jpeg";
        r.score = q.mean;
        r.min = q.min;
        r.psnr = q.psnr;
        if (heic && previousSize > 0 && Math.abs(output.length() - previousSize) < previousSize * .01) {
          // This encoder ignores the requested quality; its output cannot be tuned, so stop using HEIC.
          heicUnreliable = true;
          Files.discard(output);
          if (best != null && QualityGate.pass(mode, best.score, best.min, best.psnr)) return best;
          if (best != null) Files.discard(best.file);
          return null;
        }
        previousSize = output.length();
        boolean pass = QualityGate.pass(mode, q.mean, q.min, q.psnr);
        if (pass) {
          if (best != null) Files.discard(best.file);
          best = r;
          if (step == 0) quality = plan[1];
          else break;
        } else {
          Files.discard(output);
          if (best != null) break;
          quality = plan[2];
          if (step == 1) break;
        }
      } catch (Exception e) {
        Files.discard(output);
        if (heic) {
          if (best != null) Files.discard(best.file);
          return null;
        }
        throw e;
      }
      step++;
    }
    return best;
  }

  private static void writeHeic(File output, Bitmap bitmap, int quality, byte[] exif, int orientation)
      throws Exception {
    int rotation = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
    try (HeifWriter writer =
        new HeifWriter.Builder(output.getPath(), bitmap.getWidth(), bitmap.getHeight(),
                HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(quality)
            .setRotation(rotation)
            .build()) {
      writer.start();
      if (exif != null) {
        byte[] upright = ExifCopier.uprightCopy(exif);
        writer.addExifData(0, upright, 0, upright.length);
      }
      writer.addBitmap(bitmap);
      writer.stop(60000);
    }
  }

  private static void writeJpeg(File source, File output, Bitmap bitmap, int quality) throws Exception {
    try (FileOutputStream o = new FileOutputStream(output)) {
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, o))
        throw new IOException("JPEG encoding failed");
      o.getFD().sync();
    }
    ExifCopier.jpeg(source, output);
  }

  private static Result lossless(byte[] original, File source, File dir, Control control)
      throws Exception {
    control.check();
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(source.getPath(), bounds);
    // Coefficients take up to 3 components x 2 bytes per pixel, plus the input and output bytes.
    long needed = (long) bounds.outWidth * bounds.outHeight * 6 + original.length * 3L;
    if (needed > Runtime.getRuntime().maxMemory() * .7)
      throw new IOException("Too large for this phone's memory");
    File output = new File(dir, "lossless.jpg");
    Files.write(output, LosslessJpeg.optimize(original));
    try {
      if (output.length() > source.length() * .98)
        throw new IOException("Lossless saving is below 2%");
      PhotoQuality.Score q = PhotoQuality.compare(source, output, true);
      ExifCopier.verify(source, output);
      Result result = new Result();
      result.file = output;
      result.mime = "image/jpeg";
      result.score = q.mean;
      result.min = q.min;
      result.psnr = q.psnr;
      return result;
    } catch (Exception e) {
      Files.discard(output);
      throw e;
    }
  }
}
