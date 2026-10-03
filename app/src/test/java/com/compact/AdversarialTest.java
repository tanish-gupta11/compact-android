package com.compact;

import com.compact.photo.LosslessJpeg;
import com.compact.quality.Ssim;
import com.compact.video.*;
import com.compact.work.JobState;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;

public final class AdversarialTest {
  public static void main(String[] args) throws Exception {
    int cases = 0;
    Random rng = new Random(9824);
    for (boolean prog : new boolean[] {false, true})
      for (int sampling : new int[] {1, 2})
        for (int restart : new int[] {0, 3, 17})
          for (int shape = 0; shape < 5; shape++) {
            int w = 17 + rng.nextInt(150), h = 17 + rng.nextInt(150);
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++)
              for (int x = 0; x < w; x++)
                image.setRGB(
                    x,
                    y,
                    shape == 0
                        ? 0xff888888
                        : shape == 1
                            ? (x % 2 == 0 ? 0xff000000 : 0xffffffff)
                            : 0xff000000 | rng.nextInt(0x1000000));
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
              writer.setOutput(stream);
              ImageWriteParam p = writer.getDefaultWriteParam();
              p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
              p.setCompressionQuality(.4f + shape * .14f);
              p.setProgressiveMode(
                  prog ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
              IIOMetadata meta = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), p);
              IIOMetadataNode tree =
                  (IIOMetadataNode) meta.getAsTree("javax_imageio_jpeg_image_1.0");
              IIOMetadataNode
                  sequence = (IIOMetadataNode) tree.getElementsByTagName("markerSequence").item(0),
                  sof = (IIOMetadataNode) tree.getElementsByTagName("sof").item(0);
              IIOMetadataNode comp = (IIOMetadataNode) sof.getFirstChild();
              comp.setAttribute("HsamplingFactor", "" + sampling);
              comp.setAttribute("VsamplingFactor", "" + sampling);
              if (restart > 0) {
                IIOMetadataNode dri = new IIOMetadataNode("dri");
                dri.setAttribute("interval", "" + restart);
                sequence.appendChild(dri);
              }
              meta.setFromTree("javax_imageio_jpeg_image_1.0", tree);
              writer.write(null, new IIOImage(image, null, meta), p);
            } finally {
              writer.dispose();
            }
            byte[] source = bytes.toByteArray(), optimized = LosslessJpeg.optimize(source);
            BufferedImage a = ImageIO.read(new ByteArrayInputStream(source)),
                b = ImageIO.read(new ByteArrayInputStream(optimized));
            if (b == null
                || !Arrays.equals(
                    a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w)))
              throw new AssertionError(
                  "JPEG mismatch progressive="
                      + prog
                      + " sampling="
                      + sampling
                      + " restart="
                      + restart);
            // Verify the candidate too, even when optimize correctly chooses the smaller original.
            java.lang.reflect.Constructor<LosslessJpeg> ctor =
                LosslessJpeg.class.getDeclaredConstructor(byte[].class);
            ctor.setAccessible(true);
            Object codec = ctor.newInstance((Object) source);
            java.lang.reflect.Method decode = LosslessJpeg.class.getDeclaredMethod("decode"),
                encode = LosslessJpeg.class.getDeclaredMethod("encode");
            decode.setAccessible(true);
            encode.setAccessible(true);
            decode.invoke(codec);
            b = ImageIO.read(new ByteArrayInputStream((byte[]) encode.invoke(codec)));
            if (b == null
                || !Arrays.equals(
                    a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w)))
              throw new AssertionError(
                  "Candidate mismatch progressive="
                      + prog
                      + " sampling="
                      + sampling
                      + " restart="
                      + restart);
            cases++;
          }
    for (JobState state : JobState.values()) {
      if (state != JobState.PUBLISHED && state.canTrash())
        throw new AssertionError("Unsafe trash gate");
      if (state == JobState.PENDING && state.allows(JobState.PUBLISHED))
        throw new AssertionError("Unsafe state jump");
    }
    File bad = File.createTempFile("CompactTest_badmp4", ".mp4");
    try {
      try (DataOutputStream out = new DataOutputStream(new FileOutputStream(bad))) {
        out.writeInt(0x7fffffff);
        out.writeBytes("moov");
      }
      try {
        Mp4TimePatcher.read(bad);
        throw new AssertionError("Bad MP4 accepted");
      } catch (IOException expected) {
      }
    } finally {
      bad.delete();
    }
    int[] black = new int[64], white = new int[64];
    Arrays.fill(white, 0xffffffff);
    if (Ssim.compare(black, white, 8, 8) > .01)
      throw new AssertionError("Luminance change not detected");
    try {
      Ssim.compare(new int[8], new int[8], Integer.MAX_VALUE, 16);
      throw new AssertionError("Overflow accepted");
    } catch (IllegalArgumentException expected) {
    }
    System.out.println(
        "PASS "
            + cases
            + " adversarial JPEG cases (baseline/progressive, 4:4:4/4:2:0, restart 0/3/17,"
            + " noise/flat/edges); candidate pixels identical. State and malformed-MP4 guards"
            + " PASS.");
  }
}
