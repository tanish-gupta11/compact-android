package com.compact.photo;

import java.io.*;
import java.util.*;

/**
 * Coefficient-domain JPEG optimizer: baseline and progressive Huffman, 8-bit. APP/COM and
 * quantization bytes are retained; unsupported structures fail closed.
 */
public final class LosslessJpeg {
  private final byte[] data;
  private int pos, width, height, maxH, maxV, restart, eob;
  private boolean progressive;
  private final List<byte[]> metadata = new ArrayList<>();
  private final List<C> components = new ArrayList<>();
  private final H[][] tables = new H[2][4];

  private static final class C {
    int id, h, v, q, cols, rows, realCols, realRows, dc, dt, at;
    short[] coef;
  }

  private LosslessJpeg(byte[] data) {
    this.data = data;
  }

  public static byte[] optimize(byte[] bytes) throws IOException {
    LosslessJpeg j = new LosslessJpeg(bytes);
    j.decode();
    byte[] out = j.encode();
    return out.length < bytes.length ? out : bytes.clone();
  }

  private int u() throws IOException {
    if (pos >= data.length) throw new EOFException();
    return data[pos++] & 255;
  }

  private int word() throws IOException {
    return (u() << 8) | u();
  }

  private void decode() throws IOException {
    if (word() != 0xffd8) throw new IOException("Not JPEG");
    boolean scanned = false;
    while (pos < data.length) {
      if (u() != 255) throw new IOException("Invalid JPEG marker");
      int m;
      do {
        m = u();
      } while (m == 255);
      if (m == 217) {
        if (!scanned) throw new IOException("No scans");
        return;
      }
      int n = word() - 2, start = pos, end = pos + n;
      if (n < 0 || end > data.length) throw new IOException("Bad segment");
      if (m == 192 || m == 193 || m == 194) {
        if (!components.isEmpty()) throw new IOException("Multiple frames");
        progressive = m == 194;
        if (u() != 8) throw new IOException("Only 8-bit JPEG supported");
        height = word();
        width = word();
        int count = u();
        if (count != 1 && count != 3) throw new IOException("Only gray/RGB JPEG supported");
        for (int i = 0; i < count; i++) {
          C c = new C();
          c.id = u();
          int hv = u();
          c.h = hv >> 4;
          c.v = hv & 15;
          c.q = u();
          if (c.h < 1 || c.h > 4 || c.v < 1 || c.v > 4)
            throw new IOException("Sampling unsupported");
          maxH = Math.max(maxH, c.h);
          maxV = Math.max(maxV, c.v);
          components.add(c);
        }
        if (width < 1 || height < 1 || (long) width * height > 80_000_000)
          throw new IOException("Image too large for lossless optimizer");
        for (C c : components) {
          c.cols = ((width + 8 * maxH - 1) / (8 * maxH)) * c.h;
          c.rows = ((height + 8 * maxV - 1) / (8 * maxV)) * c.v;
          c.realCols = (width * c.h + 8 * maxH - 1) / (8 * maxH);
          c.realRows = (height * c.v + 8 * maxV - 1) / (8 * maxV);
          c.coef = new short[Math.multiplyExact(c.cols * c.rows, 64)];
        }
      } else if (m == 196) {
        while (pos < end) {
          int tc = u();
          if ((tc >> 4) > 1 || (tc & 15) > 3) throw new IOException("Bad Huffman table");
          int[] lengths = new int[17];
          int total = 0;
          for (int i = 1; i <= 16; i++) {
            lengths[i] = u();
            total += lengths[i];
          }
          int[] vals = new int[total];
          for (int i = 0; i < total; i++) vals[i] = u();
          tables[tc >> 4][tc & 15] = new H(lengths, vals);
        }
      } else if (m == 221) {
        restart = word();
        metadata.add(Arrays.copyOfRange(data, start - 4, end));
      } else if (m == 218) {
        if (components.isEmpty()) throw new IOException("Scan before frame");
        int count = u();
        C[] scan = new C[count];
        for (int i = 0; i < count; i++) {
          int id = u();
          C found = null;
          for (C c : components) if (c.id == id) found = c;
          if (found == null) throw new IOException("Bad scan component");
          scan[i] = found;
          int t = u();
          found.dt = t >> 4;
          found.at = t & 15;
          if (found.dt > 3 || found.at > 3) throw new IOException("Bad table index");
        }
        int ss = u(), se = u(), a = u();
        pos = end;
        readScan(scan, ss, se, a >> 4, a & 15);
        scanned = true;
        continue;
      } else if (m == 219 || m == 254 || (m >= 224 && m <= 239))
        metadata.add(Arrays.copyOfRange(data, start - 4, end));
      else throw new IOException("Unsupported JPEG marker " + m);
      if (pos > end) throw new IOException("Segment overflow");
      pos = end;
    }
    throw new IOException("JPEG end missing");
  }

  private void readScan(C[] scan, int ss, int se, int ah, int al) throws IOException {
    if (ss > se
        || se > 63
        || al > 13
        || ah > 13
        || (!progressive && (ss != 0 || se != 63 || ah != 0 || al != 0))
        || (progressive && ss > 0 && scan.length != 1))
      throw new IOException("Unsupported JPEG scan");
    Bits in = new Bits();
    for (C c : components) c.dc = 0;
    eob = 0;
    int cols = scan.length == 1 ? scan[0].realCols : (width + maxH * 8 - 1) / (maxH * 8),
        rows = scan.length == 1 ? scan[0].realRows : (height + maxV * 8 - 1) / (maxV * 8),
        seq = 0;
    for (int my = 0; my < rows; my++)
      for (int mx = 0; mx < cols; mx++) {
        if (restart > 0 && seq > 0 && seq % restart == 0) {
          in.align();
          if (u() != 255 || u() != 208 + ((seq / restart - 1) & 7))
            throw new IOException("Bad restart");
          for (C c : components) c.dc = 0;
          eob = 0;
        }
        for (C c : scan)
          for (int y = 0; y < (scan.length == 1 ? 1 : c.v); y++)
            for (int x = 0; x < (scan.length == 1 ? 1 : c.h); x++) {
              int bx = scan.length == 1 ? mx : mx * c.h + x,
                  by = scan.length == 1 ? my : my * c.v + y;
              short[] b = c.coef;
              int o = (by * c.cols + bx) * 64;
              if (ss == 0) {
                if (ah == 0) {
                  int n = symbol(in, tables[0][c.dt]);
                  if (n > 11) throw new IOException("Invalid DC magnitude");
                  c.dc += receive(in, n);
                  b[o] = (short) (c.dc << al);
                } else b[o] |= in.get(1) << al;
              }
              if (se == 0) continue;
              int k = Math.max(1, ss);
              if (ah == 0) {
                if (eob > 0) {
                  eob--;
                  continue;
                }
                while (k <= se) {
                  int s = symbol(in, tables[1][c.at]), r = s >> 4;
                  nop();
                  s &= 15;
                  if (s == 0) {
                    if (r == 15) {
                      k += 16;
                      continue;
                    }
                    eob = (1 << r) + in.get(r) - 1;
                    break;
                  }
                  k += r;
                  if (k > se || s > 10) throw new IOException("AC overflow");
                  b[o + k++] = (short) (receive(in, s) << al);
                }
              } else {
                int bit = 1 << al;
                if (eob == 0) {
                  while (k <= se) {
                    int rs = symbol(in, tables[1][c.at]), r = rs >> 4, s = rs & 15, newVal = 0;
                    if (s != 0) {
                      if (s != 1) throw new IOException("Bad refinement");
                      newVal = in.get(1) == 1 ? bit : -bit;
                    } else if (r != 15) {
                      eob = (1 << r) + in.get(r);
                      break;
                    }
                    while (k <= se) {
                      if (b[o + k] != 0) {
                        if (in.get(1) != 0 && (Math.abs(b[o + k]) & bit) == 0)
                          b[o + k] += b[o + k] > 0 ? bit : -bit;
                      } else {
                        if (r == 0) break;
                        r--;
                      }
                      k++;
                    }
                    if (newVal != 0) {
                      if (k > se) throw new IOException("Refinement overflow");
                      b[o + k] = (short) newVal;
                    }
                    k++;
                  }
                }
                if (eob > 0) {
                  while (k <= se) {
                    if (b[o + k] != 0 && in.get(1) != 0 && (Math.abs(b[o + k]) & bit) == 0)
                      b[o + k] += b[o + k] > 0 ? bit : -bit;
                    k++;
                  }
                  eob--;
                }
              }
            }
        seq++;
      }
    in.align();
  }

  private static void nop() {}

  private int symbol(Bits in, H h) throws IOException {
    if (h == null) throw new IOException("Missing Huffman table");
    int code = 0;
    for (int n = 1; n <= 16; n++) {
      code = (code << 1) | in.get(1);
      int index = code - h.first[n];
      if (index >= 0 && index < h.count[n]) return h.values[h.offset[n] + index];
    }
    throw new IOException("Invalid Huffman code");
  }

  private int receive(Bits b, int n) throws IOException {
    if (n == 0) return 0;
    int v = b.get(n);
    return v < (1 << (n - 1)) ? v - ((1 << n) - 1) : v;
  }

  private final class Bits {
    int value, left;

    int get(int n) throws IOException {
      int out = 0;
      while (n-- > 0) {
        if (left == 0) {
          value = u();
          if (value == 255 && u() != 0) throw new IOException("Unexpected marker in scan");
          left = 8;
        }
        out = (out << 1) | ((value >> (--left)) & 1);
      }
      return out;
    }

    void align() {
      left = 0;
    }
  }

  private static final class H {
    int[] count,
        values,
        first = new int[17],
        offset = new int[17],
        codes = new int[256],
        sizes = new int[256];

    H(int[] lengths, int[] vals) throws IOException {
      count = lengths;
      values = vals;
      int code = 0, p = 0;
      for (int n = 1; n <= 16; n++) {
        first[n] = code;
        offset[n] = p;
        for (int i = 0; i < count[n]; i++) {
          if (code >= (1 << n) || p >= vals.length)
            throw new IOException("Oversubscribed Huffman table");
          int v = vals[p++];
          codes[v] = code++;
          sizes[v] = n;
        }
        code <<= 1;
      }
    }
  }

  private byte[] encode() throws IOException {
    long[][] freq = new long[2][256];
    visit((type, s, bits, n) -> freq[type][s]++);
    H[] ht = {optimal(freq[0]), optimal(freq[1])};
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeShort(0xffd8);
    for (byte[] m : metadata) out.write(m);
    out.writeShort(0xffc0);
    out.writeShort(8 + 3 * components.size());
    out.writeByte(8);
    out.writeShort(height);
    out.writeShort(width);
    out.writeByte(components.size());
    for (C c : components) {
      out.writeByte(c.id);
      out.writeByte((c.h << 4) | c.v);
      out.writeByte(c.q);
    }
    for (int t = 0; t < 2; t++) {
      H h = ht[t];
      out.writeShort(0xffc4);
      out.writeShort(19 + h.values.length);
      out.writeByte(t << 4);
      for (int n = 1; n <= 16; n++) out.writeByte(h.count[n]);
      for (int v : h.values) out.writeByte(v);
    }
    out.writeShort(0xffda);
    out.writeShort(6 + 2 * components.size());
    out.writeByte(components.size());
    for (C c : components) {
      out.writeByte(c.id);
      out.writeByte(0);
    }
    out.writeByte(0);
    out.writeByte(63);
    out.writeByte(0);
    Writer bits = new Writer(out);
    visit(
        new Sink() {
          public void token(int t, int s, int v, int n) throws IOException {
            bits.put(ht[t].codes[s], ht[t].sizes[s]);
            bits.put(v, n);
          }

          public void restart(int n) throws IOException {
            bits.flush();
            out.writeShort(0xffd0 + (n & 7));
          }
        });
    bits.flush();
    out.writeShort(0xffd9);
    return bytes.toByteArray();
  }

  private interface Sink {
    void token(int type, int symbol, int bits, int length) throws IOException;

    default void restart(int n) throws IOException {}
  }

  private void visit(Sink sink) throws IOException {
    int[] dc = new int[components.size()];
    int cols = (width + maxH * 8 - 1) / (maxH * 8),
        rows = (height + maxV * 8 - 1) / (maxV * 8),
        seq = 0;
    for (int my = 0; my < rows; my++)
      for (int mx = 0; mx < cols; mx++) {
        if (restart > 0 && seq > 0 && seq % restart == 0) {
          Arrays.fill(dc, 0);
          sink.restart(seq / restart - 1);
        }
        int ci = 0;
        for (C c : components) {
          for (int y = 0; y < c.v; y++)
            for (int x = 0; x < c.h; x++) {
              short[] b = c.coef;
              int o = ((my * c.v + y) * c.cols + mx * c.h + x) * 64;
              int d = b[o] - dc[ci];
              dc[ci] = b[o];
              int n = magnitude(d);
              if (n > 11) throw new IOException("Baseline DC overflow");
              sink.token(0, n, d < 0 ? d - 1 : d, n);
              int run = 0;
              for (int k = 1; k < 64; k++) {
                if (b[o + k] == 0) {
                  run++;
                  continue;
                }
                while (run >= 16) {
                  sink.token(1, 240, 0, 0);
                  run -= 16;
                }
                n = magnitude(b[o + k]);
                if (n > 10) throw new IOException("Baseline AC overflow");
                sink.token(1, (run << 4) | n, b[o + k] < 0 ? b[o + k] - 1 : b[o + k], n);
                run = 0;
              }
              if (run > 0) sink.token(1, 0, 0, 0);
            }
          ci++;
        }
        seq++;
      }
  }

  private static int magnitude(int x) {
    return x == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(x));
  }

  private static H optimal(long[] freq) throws IOException {
    long[] f = Arrays.copyOf(freq, 257);
    f[256] = 1;
    int[] depth = new int[257], next = new int[257];
    Arrays.fill(next, -1);
    while (true) {
      int a = -1, b = -1;
      for (int i = 0; i < 257; i++)
        if (f[i] > 0) {
          if (a < 0 || f[i] < f[a]) {
            b = a;
            a = i;
          } else if (b < 0 || f[i] < f[b]) b = i;
        }
      if (b < 0) break;
      f[a] += f[b];
      f[b] = 0;
      int x = a;
      depth[x]++;
      while (next[x] >= 0) {
        x = next[x];
        depth[x]++;
      }
      next[x] = b;
      x = b;
      depth[x]++;
      while (next[x] >= 0) {
        x = next[x];
        depth[x]++;
      }
    }
    int[] counts = new int[258];
    for (int d : depth) if (d > 0) counts[d]++;
    for (int i = 257; i > 16; i--)
      while (counts[i] > 0) {
        int j = i - 2;
        while (j > 0 && counts[j] == 0) j--;
        if (j == 0) throw new IOException("Huffman length limit");
        counts[i] -= 2;
        counts[i - 1]++;
        counts[j + 1] += 2;
        counts[j]--;
      }
    int longest = 16;
    while (counts[longest] == 0) longest--;
    counts[longest]--;
    List<Integer> symbols = new ArrayList<>();
    for (int i = 0; i < 256; i++) if (freq[i] > 0) symbols.add(i);
    symbols.sort(
        (a, b) -> {
          int d = Long.compare(freq[b], freq[a]);
          return d == 0 ? Integer.compare(a, b) : d;
        });
    int[] vals = new int[symbols.size()];
    for (int i = 0; i < vals.length; i++) vals[i] = symbols.get(i);
    return new H(Arrays.copyOf(counts, 17), vals);
  }

  private static final class Writer {
    final DataOutputStream out;
    int value, count;

    Writer(DataOutputStream out) {
      this.out = out;
    }

    void put(int bits, int n) throws IOException {
      for (int i = n - 1; i >= 0; i--) {
        value = (value << 1) | ((bits >> i) & 1);
        if (++count == 8) {
          out.writeByte(value);
          if ((value & 255) == 255) out.writeByte(0);
          value = count = 0;
        }
      }
    }

    void flush() throws IOException {
      if (count > 0) put((1 << (8 - count)) - 1, 8 - count);
    }
  }
}
