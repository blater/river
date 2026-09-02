package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Transactionally admitted retained buffers for grouped execution. */
final class SqlGroupedBuffers {
  private final SqlRetainedArrayAllocator allocator;
  long[] values = new long[0];
  long[] highs = new long[0];
  ByteBuffer text;
  char[] characters;

  SqlGroupedBuffers() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlGroupedBuffers(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode prepare(int columns, boolean textRequired) {
    int capacity = BoundedArrayGrowth.capacity(
        values.length, columns, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == values.length && (!textRequired || text != null)) {
      return StatusCode.OK;
    }
    try {
      long[] nextValues = capacity == values.length
          ? values : allocator.longs(capacity);
      long[] nextHighs = capacity == highs.length
          ? highs : allocator.longs(capacity);
      ByteBuffer nextText = !textRequired || text != null
          ? text : allocator.direct(Utf8Text.MAXIMUM_BYTES);
      char[] nextCharacters = !textRequired || characters != null
          ? characters : allocator.characters(Utf8Text.MAXIMUM_BUFFER_CHARACTERS);
      values = nextValues;
      highs = nextHighs;
      text = nextText;
      characters = nextCharacters;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
