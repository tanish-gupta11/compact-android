package com.compact.video;

import java.util.regex.*;

public final class Location {
  public static float[] parse(String value) {
    if (value == null || value.isEmpty()) return null;
    Matcher m =
        Pattern.compile(
                "^([+-][0-9]+(?:\\.[0-9]+)?)([+-][0-9]+(?:\\.[0-9]+)?)(?:[+-][0-9]+(?:\\.[0-9]+)?)?/?$")
            .matcher(value);
    if (!m.matches()) throw new IllegalArgumentException("Unrecognized GPS format");
    float lat = Float.parseFloat(m.group(1)), lon = Float.parseFloat(m.group(2));
    if (!Float.isFinite(lat) || !Float.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
      throw new IllegalArgumentException("GPS out of range");
    return new float[] {lat, lon};
  }
}
