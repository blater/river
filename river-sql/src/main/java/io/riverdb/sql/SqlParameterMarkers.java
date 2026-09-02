package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.util.Arrays;

/** One-pass statement-global parameter-marker index retained by compiled syntax. */
public final class SqlParameterMarkers {
  private int[] offsets = new int[0];
  private int count;

  public StatusCode scan(CharSequence sql) {
    count = 0;
    if (sql == null || sql.length() == 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    boolean quoted = false;
    for (int offset = 0; offset < sql.length(); offset++) {
      char value = sql.charAt(offset);
      if (value == '\'' && quoted && offset + 1 < sql.length()
          && sql.charAt(offset + 1) == '\'') {
        offset++;
      } else if (value == '\'') {
        quoted = !quoted;
      } else if (!quoted && value == '?') {
        if (count == SqlShapeLimits.MAX_PARAMETERS || !reserve(count + 1)) {
          count = 0;
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        offsets[count++] = offset;
      }
    }
    if (quoted) {
      count = 0;
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  public int count() { return count; }

  int ordinalAt(int originalOffset) {
    int low = 0;
    int high = count - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      int candidate = offsets[middle];
      if (candidate < originalOffset) low = middle + 1;
      else if (candidate > originalOffset) high = middle - 1;
      else return middle;
    }
    return -1;
  }

  private boolean reserve(int required) {
    if (required <= offsets.length) return true;
    int capacity = Math.min(
        SqlShapeLimits.MAX_PARAMETERS, Math.max(8, Math.max(required, offsets.length * 2)));
    try {
      offsets = Arrays.copyOf(offsets, capacity);
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }
}
