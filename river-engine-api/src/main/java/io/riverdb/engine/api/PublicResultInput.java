package io.riverdb.engine.api;

import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Trust-boundary validation for caller-provided public result lane arrays. */
final class PublicResultInput {
  private PublicResultInput() {
  }

  static boolean legacy(
      long[] values, long nullMask, int[] descriptors, int columns) {
    return columns <= Long.SIZE && sources(values, descriptors, columns)
        && (nullMask & ~allowedBits(columns)) == 0
        && PublicResultValueValidation.compact(
            values, descriptors, columns, null, nullMask);
  }

  static boolean words(
      long[] values,
      long[] nullWords,
      int nullWordCount,
      int[] descriptors,
      int columns) {
    int requiredWords = wordCount(columns);
    return sources(values, descriptors, columns)
        && nullWords != null && nullWordCount == requiredWords
        && nullWordCount <= nullWords.length
        && (requiredWords == 0
            || (nullWords[requiredWords - 1] & ~allowedTrailingBits(columns)) == 0)
        && PublicResultValueValidation.compact(
            values, descriptors, columns, nullWords, 0);
  }

  static boolean words(
      long[] highValues,
      long[] values,
      long[] nullWords,
      int nullWordCount,
      int[] descriptors,
      int columns) {
    int requiredWords = wordCount(columns);
    return sources(values, descriptors, columns)
        && highValues != null && columns <= highValues.length
        && nullWords != null && nullWordCount == requiredWords
        && nullWordCount <= nullWords.length
        && (requiredWords == 0
            || (nullWords[requiredWords - 1] & ~allowedTrailingBits(columns)) == 0)
        && PublicResultValueValidation.decimal128(
            highValues, values, descriptors, columns, nullWords);
  }

  static int wordCount(int columns) {
    return columns < 0 ? -1 : (int) (((long) columns + Long.SIZE - 1) / Long.SIZE);
  }

  private static boolean sources(long[] values, int[] descriptors, int columns) {
    if (columns < 0 || columns > SqlShapeLimits.MAX_RESULT_COLUMNS
        || columns != 0 && (values == null || descriptors == null)
        || values != null && columns > values.length
        || descriptors != null && columns > descriptors.length) {
      return false;
    }
    for (int index = 0; index < columns; index++) {
      if (!SqlTypeDescriptor.isValid(descriptors[index])) return false;
    }
    return true;
  }

  private static long allowedBits(int columns) {
    return columns == Long.SIZE ? -1L : columns == 0 ? 0 : (1L << columns) - 1;
  }

  private static long allowedTrailingBits(int columns) {
    int trailing = columns & 63;
    return trailing == 0 ? -1L : (1L << trailing) - 1;
  }
}
