package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Stable block-store codec facade over dynamic record storage. */
final class SqlBlockRowCodec {
  static final int HEADER_BYTES = SqlBlockRowRecordCodec.HEADER_BYTES;
  static final int TRAILER_BYTES = SqlBlockRowRecordCodec.TRAILER_BYTES;
  static final int MAXIMUM_RECORD_BYTES = SqlBlockRowRecordCodec.MAXIMUM_RECORD_BYTES;

  private final SqlBlockRowRecordCodec codec;

  SqlBlockRowCodec() { this(null); }

  SqlBlockRowCodec(SqlSessionShapeBudget budget) {
    codec = new SqlBlockRowRecordCodec(budget);
  }

  StatusCode encode(SqlBlockRow source, SqlBlockSchema schema, long ordinal) {
    return codec.encode(source, schema, ordinal);
  }
  StatusCode decode(SqlBlockRow destination, SqlBlockSchema schema, long ordinal) {
    return codec.decode(destination, schema, ordinal);
  }
  StatusCode prepareRead(int length) { return codec.prepareRead(length); }
  ByteBuffer buffer() { return codec.buffer(); }
  int capacity() { return codec.capacity(); }
  void eraseScratch() { codec.eraseScratch(); }
  void reset() { codec.reset(); }
  void close() { codec.close(); }
}
