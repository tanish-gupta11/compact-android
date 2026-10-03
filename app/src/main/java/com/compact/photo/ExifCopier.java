package com.compact.photo;

import android.media.ExifInterface;
import com.compact.util.Files;
import java.io.*;
import java.util.*;

public final class ExifCopier {
  public static List<byte[]> segments(byte[] b) throws IOException {
    List<byte[]> out = new ArrayList<>();
    if (b.length < 4 || (b[0] & 255) != 255 || (b[1] & 255) != 216)
      throw new IOException("Not a JPEG");
    int p = 2;
    while (p + 4 <= b.length) {
      int start = p;
      if ((b[p++] & 255) != 255) throw new IOException("Bad JPEG");
      int m = b[p++] & 255;
      if (m == 218) return out;
      int n = ((b[p] & 255) << 8) | (b[p + 1] & 255);
      if (n < 2 || p + n > b.length) throw new IOException("Bad JPEG segment");
      if (m >= 224 && m <= 239 || m == 254) {
        byte[] segment = Arrays.copyOfRange(b, start, p + n);
        String text = new String(segment, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (text.contains("MotionPhoto")
            || text.contains("MicroVideo")
            || text.contains("hdrgm:")
            || m == 226 && text.contains("MPF"))
          throw new IOException(
              "Motion photo, gain map or multi-picture JPEG is preserved unchanged");
        out.add(segment);
      }
      p += n;
    }
    throw new IOException("JPEG scan missing");
  }

  public static byte[] exif(List<byte[]> segments) {
    for (byte[] b : segments)
      if ((b[1] & 255) == 225
          && b.length > 10
          && b[4] == 'E'
          && b[5] == 'x'
          && b[6] == 'i'
          && b[7] == 'f') return Arrays.copyOfRange(b, 4, b.length);
    return null;
  }

  public static void jpeg(File input, File output) throws Exception {
    List<byte[]> parts = segments(Files.read(input));
    byte[] encoded = Files.read(output);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(encoded, 0, 2);
    for (byte[] p : parts) out.write(p);
    int pos = 2;
    while (pos + 4 < encoded.length) {
      int m = encoded[pos + 1] & 255;
      if (m == 218) break;
      int n = ((encoded[pos + 2] & 255) << 8) | (encoded[pos + 3] & 255);
      if (n < 2 || pos + 2 + n > encoded.length) throw new IOException("Bad encoded JPEG");
      if (!(m >= 224 && m <= 239) && m != 254) out.write(encoded, pos, n + 2);
      pos += n + 2;
    }
    out.write(encoded, pos, encoded.length - pos);
    Files.write(output, out.toByteArray());
  }

  public static void verify(File src, File dst) throws Exception {
    ExifInterface a = new ExifInterface(src), b = new ExifInterface(dst);
    // HEIC stores rotation natively (irot) with EXIF Orientation 1; compare the effective rotation.
    boolean nativeRotation = dst.getName().endsWith(".heic");
    if (nativeRotation && degrees(a) != heifRotation(dst))
      throw new IOException("Photo rotation differs");
    for (java.lang.reflect.Field f : ExifInterface.class.getFields())
      if (f.getName().startsWith("TAG_") && f.getType() == String.class) {
        String key = (String) f.get(null);
        if (key.equals("ImageWidth")
            || key.equals("ImageLength")
            || key.equals("PixelXDimension")
            || key.equals("PixelYDimension")
            || key.equals("JPEGInterchangeFormat")
            || key.equals("JPEGInterchangeFormatLength")
            || key.equals("StripOffsets")
            || key.equals("StripByteCounts")
            || nativeRotation && key.equals("Orientation")) continue;
        String av = a.getAttribute(key);
        if (av != null && !av.equals(b.getAttribute(key)))
          throw new IOException("Metadata differs: " + key);
      }
  }

  private static int heifRotation(File f) throws Exception {
    android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
    try {
      r.setDataSource(f.getPath());
      String v = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_IMAGE_ROTATION);
      return v == null ? 0 : Integer.parseInt(v.trim());
    } finally {
      r.release();
    }
  }

  private static int degrees(ExifInterface e) {
    switch (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
      case 6:
        return 90;
      case 3:
        return 180;
      case 8:
        return 270;
      default:
        return 0;
    }
  }

  /** EXIF Orientation of the JPEG (1 when absent or unreadable). */
  public static int orientation(byte[] exif) {
    int at = orientationOffset(exif);
    if (at < 0) return 1;
    boolean le = exif[6] == 'I';
    return le ? (exif[at] & 255) | (exif[at + 1] & 255) << 8 : (exif[at] & 255) << 8 | (exif[at + 1] & 255);
  }

  /** Copy of the EXIF block with Orientation set to 1 (used when rotation is stored natively). */
  public static byte[] uprightCopy(byte[] exif) {
    byte[] out = exif.clone();
    int at = orientationOffset(out);
    if (at >= 0) {
      boolean le = out[6] == 'I';
      out[at] = (byte) (le ? 1 : 0);
      out[at + 1] = (byte) (le ? 0 : 1);
    }
    return out;
  }

  /** Byte offset of the Orientation SHORT value in IFD0 of an "Exif header + TIFF block, or -1. */
  private static int orientationOffset(byte[] e) {
    if (e == null || e.length < 14 || e[0] != 'E' || e[1] != 'x') return -1;
    int t = 6;
    boolean le = e[t] == 'I' && e[t + 1] == 'I';
    if (!le && !(e[t] == 'M' && e[t + 1] == 'M')) return -1;
    long ifd = read(e, t + 4, 4, le);
    int p = (int) (t + ifd);
    if (ifd < 8 || p + 2 > e.length) return -1;
    int count = (int) read(e, p, 2, le);
    for (int i = 0; i < count; i++) {
      int entry = p + 2 + i * 12;
      if (entry + 12 > e.length) return -1;
      if (read(e, entry, 2, le) == 0x0112 && read(e, entry + 2, 2, le) == 3) return entry + 8;
    }
    return -1;
  }

  private static long read(byte[] b, int at, int n, boolean le) {
    long v = 0;
    for (int i = 0; i < n; i++) v |= (long) (b[at + i] & 255) << (8 * (le ? i : n - 1 - i));
    return v;
  }

  public static boolean heicSafe(List<byte[]> parts) {
    for (byte[] p : parts) {
      int m = p[1] & 255;
      if (m == 225 && (p.length < 10 || p[4] != 'E' || p[5] != 'x')) return false;
      if (m == 226 || m == 238 || m == 254) return false;
    }
    return true;
  }
}
