package com.compact;

import com.compact.photo.LosslessJpeg;
import com.compact.quality.Ssim;
import com.compact.video.*;
import com.compact.work.JobState;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;

public final class CoreTest {
  private static int checks;
  private static boolean extended;

  private static void check(boolean v, String msg) {
    checks++;
    if (!v) throw new AssertionError(msg);
  }

  private static double luma(int v) {
    return .299 * ((v >> 16) & 255) + .587 * ((v >> 8) & 255) + .114 * (v & 255);
  }

  private static double referenceSsim(int[] a, int[] b, int w, int h) {
    double sum = 0;
    int n = 0;
    for (int y = 0; y + 8 <= h; y += 4)
      for (int x = 0; x + 8 <= w; x += 4) {
        double ma = 0, mb = 0;
        for (int j = 0; j < 8; j++)
          for (int i = 0; i < 8; i++) {
            ma += luma(a[(y + j) * w + x + i]);
            mb += luma(b[(y + j) * w + x + i]);
          }
        ma /= 64;
        mb /= 64;
        double va = 0, vb = 0, cov = 0;
        for (int j = 0; j < 8; j++)
          for (int i = 0; i < 8; i++) {
            double da = luma(a[(y + j) * w + x + i]) - ma, db = luma(b[(y + j) * w + x + i]) - mb;
            va += da * da;
            vb += db * db;
            cov += da * db;
          }
        va /= 63;
        vb /= 63;
        cov /= 63;
        sum += ((2 * ma * mb + 6.5025) * (2 * cov + 58.5225)) / ((ma * ma + mb * mb + 6.5025) * (va + vb + 58.5225));
        n++;
      }
    return n == 0 ? 0 : Math.max(0, Math.min(1, sum / n));
  }

  private static double referencePsnr(int[] a, int[] b) {
    double se = 0;
    for (int i = 0; i < a.length; i++) {
      double d = luma(a[i]) - luma(b[i]);
      se += d * d;
    }
    double mse = se / a.length;
    return mse <= 1e-10 ? 100 : 10 * Math.log10(255 * 255 / mse);
  }

  public static void main(String[] args) throws Exception {
    Random r = new Random(41);
    int[] a = new int[4096], b = new int[a.length], c = new int[a.length];
    for (int i = 0; i < a.length; i++) {
      int v = 50 + r.nextInt(150);
      a[i] = 0xff000000 | v * 0x10101;
      b[i] = 0xff000000 | (v + r.nextInt(5) - 2) * 0x10101;
      c[i] = 0xff000000 | (v + r.nextInt(61) - 30) * 0x10101;
    }
    double identical = Ssim.compare(a, a, 64, 64),
        small = Ssim.compare(a, b, 64, 64),
        large = Ssim.compare(a, c, 64, 64);
    check(identical == 1, "Identity");
    check(small > large && small < 1, "Noise monotonic");
    int[] shifted = a.clone();
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 64; x++) shifted[y * 64 + x] = a[y * 64 + (x + 1) % 64];
    check(Ssim.compare(a, shifted, 64, 64) < .2, "Spatial shift detected");
    // The fast block-sum SSIM must match the direct per-window definition on odd sizes too.
    for (int[] size : new int[][] {{64, 64}, {37, 29}, {8, 8}, {130, 67}}) {
      int w = size[0], h = size[1];
      int[] p = new int[w * h], q = new int[w * h];
      for (int i = 0; i < p.length; i++) {
        p[i] = 0xff000000 | r.nextInt(0xffffff);
        int d = r.nextInt(31) - 15;
        q[i] = 0xff000000 | (Math.max(0, Math.min(255, ((p[i] >> 16) & 255) + d)) << 16) | (p[i] & 0xffff);
      }
      Ssim.Result m = Ssim.measure(p, q, w, h);
      check(Math.abs(m.ssim - referenceSsim(p, q, w, h)) < 1e-9, "Fast SSIM matches reference " + w + "x" + h);
      check(Math.abs(m.psnr() - referencePsnr(p, q)) < 1e-9, "PSNR matches reference");
    }
    check(Ssim.measure(a, a, 64, 64).psnr() == 100, "Identical PSNR");
    System.out.println(
        "SSIM identity=" + identical + " small noise=" + small + " large noise=" + large);
    check(BitrateCalculator.smartBitsPerSecond(1920, 1080, 30) == 6220800, "1080 bitrate");
    check(Location.parse("+12.34-123.45/")[1] == -123.45f, "GPS");
    try {
      Location.parse("+91+200/");
      throw new AssertionError();
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    check(JobState.ENCODING.recovered() == JobState.PENDING, "Encoding recovery");
    check(JobState.PUBLISHED.recovered() == JobState.PUBLISHED, "No re-encoding published");
    check(!JobState.VERIFIED.canTrash(), "No premature trash");
    check(JobState.DONE.allows(JobState.PUBLISHED), "Reviewed kept copy can request Trash");
    check(!JobState.DONE.canTrash(), "Kept copy cannot be trashed before review");
    check(JobState.PUBLISHED.canTrash(), "Reviewed published copy can request Trash");
    for (boolean progressive : new boolean[] {false, true})
      for (float quality : new float[] {.5f, .9f, 1f})
        for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_GRAY}) {
          BufferedImage im = new BufferedImage(257, 193, type);
          for (int y = 0; y < 193; y++)
            for (int x = 0; x < 257; x++)
              im.setRGB(
                  x,
                  y,
                  0xff000000
                      | (((x * 7 + y * 3) & 255) << 16)
                      | (((x + y * 2) & 255) << 8)
                      | ((x * 2 + y * 3) & 255));
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
          try (ImageOutputStream os = ImageIO.createImageOutputStream(bytes)) {
            w.setOutput(os);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            p.setProgressiveMode(
                progressive ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
            w.write(null, new IIOImage(im, null, null), p);
          } finally {
            w.dispose();
          }
          byte[] src = bytes.toByteArray();
          byte[] marker = {(byte) 255, (byte) 225, 0, 10, 69, 120, 105, 102, 0, 0, 1, 2};
          ByteArrayOutputStream tagged = new ByteArrayOutputStream();
          tagged.write(src, 0, 2);
          tagged.write(marker);
          tagged.write(src, 2, src.length - 2);
          src = tagged.toByteArray();
          byte[] out = LosslessJpeg.optimize(src);
          check(out.length <= src.length, "Not larger");
          BufferedImage original = ImageIO.read(new ByteArrayInputStream(src)),
              result = ImageIO.read(new ByteArrayInputStream(out));
          check(result != null, "JPEG decodes");
          check(
              Arrays.equals(
                  original.getRGB(0, 0, 257, 193, null, 0, 257),
                  result.getRGB(0, 0, 257, 193, null, 0, 257)),
              "Pixel identity progressive=" + progressive + " q=" + quality);
          check(indexOf(out, marker) >= 0, "EXIF bytes unchanged");
          System.out.println(
              "JPEG progressive="
                  + progressive
                  + " type="
                  + type
                  + " quality="
                  + quality
                  + " bytes="
                  + src.length
                  + " -> "
                  + out.length
                  + " pixels identical");
        }
    try {
      LosslessJpeg.optimize(new byte[] {1, 2, 3});
      throw new AssertionError("Corrupt accepted");
    } catch (IOException expected) {
      checks++;
    }
    for (int variant = 0; variant < 4; variant++) {
      int version = variant & 1;
      extended = variant >= 2;
      File src = File.createTempFile("CompactTest_time", ".mp4"),
          dst = File.createTempFile("CompactTest_time", ".mp4");
      try {
        write(src, mp4(version, 12345));
        write(dst, mp4(version, 44444));
        Mp4TimePatcher.copy(src, dst);
        check(
            Arrays.equals(Mp4TimePatcher.read(src), Mp4TimePatcher.read(dst)),
            "MP4 time v" + version);
        check(
            Arrays.equals(
                java.nio.file.Files.readAllBytes(src.toPath()),
                java.nio.file.Files.readAllBytes(dst.toPath())),
            "Every MP4 track time patched; extended=" + extended);
      } finally {
        src.delete();
        dst.delete();
      }
    }
    System.out.println("PASS " + checks + " assertions");
  }

  private static int indexOf(byte[] a, byte[] b) {
    outer:
    for (int i = 0; i <= a.length - b.length; i++) {
      for (int j = 0; j < b.length; j++) if (a[i + j] != b[j]) continue outer;
      return i;
    }
    return -1;
  }

  private static void write(File f, byte[] b) throws IOException {
    try (FileOutputStream o = new FileOutputStream(f)) {
      o.write(b);
    }
  }

  private static byte[] box(String type, byte[] payload) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.writeInt(extended ? 1 : 8 + payload.length);
    d.writeBytes(type);
    if (extended) d.writeLong(16L + payload.length);
    d.write(payload);
    return b.toByteArray();
  }

  private static byte[] mp4(int version, long time) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.writeInt(version << 24);
    if (version == 0) {
      d.writeInt((int) time);
      d.writeInt((int) time + 1);
    } else {
      d.writeLong(time);
      d.writeLong(time + 1);
    }
    byte[] payload = b.toByteArray();
    b.reset();
    b.write(box("mvhd", payload));
    ByteArrayOutputStream track = new ByteArrayOutputStream();
    track.write(box("tkhd", payload));
    track.write(box("mdia", box("mdhd", payload)));
    b.write(box("trak", track.toByteArray()));
    return box("moov", b.toByteArray());
  }
}
