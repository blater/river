package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free façade for casts between primitive temporal values and canonical text. */
public final class LocalTemporalCast {
  public static final int MAXIMUM_TEXT_CHARACTERS = 32;

  private LocalTemporalCast() {
  }

  public static StatusCode castFixed(
      long value, int sourceDescriptor, int targetDescriptor, LocalTemporal.Value result) {
    return LocalTemporalFixedCast.cast(value, sourceDescriptor, targetDescriptor, result);
  }

  public static StatusCode parseText(
      CharSequence text,
      int start,
      int end,
      int targetDescriptor,
      LocalTemporal.Value result) {
    return LocalTemporalTextParser.parse(text, start, end, targetDescriptor, result);
  }

  public static StatusCode formatText(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      char[] target,
      int offset,
      TextResult result) {
    return LocalTemporalTextFormatter.format(
        value, sourceDescriptor, targetDescriptor, target, offset, result);
  }

  public static int canonicalLength(int descriptor) {
    return LocalTemporalTextFormatter.canonicalLength(descriptor);
  }

  /** Reusable formatted-text carrier. */
  public static final class TextResult {
    public int length;
  }
}
