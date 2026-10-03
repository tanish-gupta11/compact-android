package com.compact.quality;

/**
 * Pure-Java image similarity for packed ARGB pixels: mean SSIM over 8x8 luma windows with stride 4
 * (Wang et al. 2004 constants) plus luma PSNR. Windows are assembled from 4x4 block sums, so each
 * pixel is read once instead of eight times.
 */
public final class Ssim {
  private static final double C1 = 6.5025, C2 = 58.5225;

  public static final class Result {
    /** Mean SSIM over all windows, in [0, 1]. */
    public double ssim;
    /** Number of 8x8 windows that contributed to {@link #ssim}. */
    public long windows;
    /** Sum of squared luma differences and the pixel count, for combining PSNR across tiles. */
    public double squaredError;
    public long pixels;

    public double psnr() {
      return Ssim.psnr(squaredError, pixels);
    }
  }

  private Ssim() {}

  public static double compare(int[] a, int[] b, int width, int height) {
    return measure(a, b, width, height).ssim;
  }

  /** PSNR in dB from a summed squared error; 100 means identical. */
  public static double psnr(double squaredError, long pixels) {
    if (pixels <= 0) return 0;
    double mse = squaredError / pixels;
    return mse <= 1e-10 ? 100 : 10 * Math.log10(255 * 255 / mse);
  }

  public static Result measure(int[] a, int[] b, int width, int height) {
    if (a == null
        || b == null
        || width < 8
        || height < 8
        || a.length < (long) width * height
        || b.length < (long) width * height)
      throw new IllegalArgumentException("Images must be at least 8x8 and the same size");
    int bw = width / 4, bh = height / 4, n = bw * bh;
    double[] sa = new double[n], sb = new double[n], saa = new double[n], sbb = new double[n],
        sab = new double[n];
    Result r = new Result();
    for (int y = 0; y < height; y++) {
      int by = y / 4;
      for (int x = 0; x < width; x++) {
        double la = luma(a[y * width + x]), lb = luma(b[y * width + x]), d = la - lb;
        r.squaredError += d * d;
        int bx = x / 4;
        if (by < bh && bx < bw) {
          int k = by * bw + bx;
          sa[k] += la;
          sb[k] += lb;
          saa[k] += la * la;
          sbb[k] += lb * lb;
          sab[k] += la * lb;
        }
      }
    }
    r.pixels = (long) width * height;
    double sum = 0;
    for (int y = 0; y + 1 < bh; y++)
      for (int x = 0; x + 1 < bw; x++) {
        int k = y * bw + x, k1 = k + 1, k2 = k + bw, k3 = k2 + 1;
        double ta = sa[k] + sa[k1] + sa[k2] + sa[k3], tb = sb[k] + sb[k1] + sb[k2] + sb[k3];
        double taa = saa[k] + saa[k1] + saa[k2] + saa[k3], tbb = sbb[k] + sbb[k1] + sbb[k2] + sbb[k3];
        double tab = sab[k] + sab[k1] + sab[k2] + sab[k3];
        double ma = ta / 64, mb = tb / 64;
        double va = (taa - ta * ma) / 63, vb = (tbb - tb * mb) / 63;
        double cov = (tab - ta * mb) / 63;
        sum += ((2 * ma * mb + C1) * (2 * cov + C2)) / ((ma * ma + mb * mb + C1) * (va + vb + C2));
        r.windows++;
      }
    r.ssim = r.windows == 0 ? 0 : Math.max(0, Math.min(1, sum / r.windows));
    return r;
  }

  private static double luma(int argb) {
    return .299 * ((argb >> 16) & 255) + .587 * ((argb >> 8) & 255) + .114 * (argb & 255);
  }
}
