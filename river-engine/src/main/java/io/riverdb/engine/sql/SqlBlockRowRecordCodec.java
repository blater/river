package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Encodes and validates one canonical block-store row record. */
final class SqlBlockRowRecordCodec {
  static final int HEADER_BYTES = Integer.BYTES + Long.BYTES;
  static final int TRAILER_BYTES = Integer.BYTES;
  static final int MAXIMUM_RECORD_BYTES =
      HEADER_BYTES + SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES + TRAILER_BYTES;
  private static final int WARM_RECORD_BYTES = 20 * 1_024;

  private final TextView text = new TextView();
  private final CRC32C checksum = new CRC32C();
  private final SqlSessionShapeBudget budget;
  private ByteBuffer record;
  private int highWater;

  SqlBlockRowRecordCodec() { this(null); }

  SqlBlockRowRecordCodec(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode encode(SqlBlockRow source, SqlBlockSchema schema, long ordinal) {
    int required = maximumEncodedBytes(source, schema);
    if (required < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode admitted = reserve(required);
    if (!admitted.isOk()) return admitted;
    record.clear();
    record.position(HEADER_BYTES);
    for (int word = 0; word < source.nullWordCount(); word++) {
      record.putLong(source.nullWord(word));
    }
    for (int column = 0; column < schema.count(); column++) {
      if (SqlTypeDescriptor.isWideDecimal(schema.descriptor(column))) {
        record.putLong(source.highValue(column));
      }
      record.putLong(schema.varchar(column) ? 0 : source.value(column));
    }
    for (int column = 0; column < schema.count(); column++) {
      StatusCode status = encodeText(source, schema, column);
      if (!status.isOk()) {
        text.clear();
        return status;
      }
    }
    text.clear();
    int payload = record.position() - HEADER_BYTES;
    record.putInt(0, payload);
    record.putLong(Integer.BYTES, ordinal);
    record.putInt(checksum(HEADER_BYTES, payload));
    record.flip();
    highWater = Math.max(highWater, record.limit());
    return StatusCode.OK;
  }

  StatusCode decode(SqlBlockRow destination, SqlBlockSchema schema, long ordinal) {
    int payload = record.getInt(0);
    if (payload <= 0
        || record.getLong(Integer.BYTES) != ordinal
        || record.limit() != HEADER_BYTES + payload + TRAILER_BYTES
        || record.getInt(HEADER_BYTES + payload) != checksum(HEADER_BYTES, payload)
        || minimumPayloadBytes(schema) > payload) {
      return StatusCode.CORRUPTION;
    }
    record.position(HEADER_BYTES);
    StatusCode admitted = destination.reset(schema.count());
    if (!admitted.isOk()) return admitted;
    int nullWords = (schema.count() + Long.SIZE - 1) >>> 6;
    for (int word = 0; word < nullWords; word++) {
      long nullWord = record.getLong();
      int trailing = schema.count() & 63;
      if (word == nullWords - 1 && trailing != 0
          && (nullWord & ~((1L << trailing) - 1)) != 0) {
        return StatusCode.CORRUPTION;
      }
      for (int bit = 0; bit < Long.SIZE; bit++) {
        int column = (word << 6) + bit;
        if (column >= schema.count()) break;
        if ((nullWord & 1L << bit) != 0) destination.setNull(column);
      }
    }
    for (int column = 0; column < schema.count(); column++) {
      long high = SqlTypeDescriptor.isWideDecimal(schema.descriptor(column))
          ? record.getLong() : 0;
      long value = record.getLong();
      if (!destination.nullValue(column)) {
        if (SqlTypeDescriptor.isWideDecimal(schema.descriptor(column))) {
          destination.setDecimal128(column, high, value);
        } else destination.setValue(column, value);
      }
    }
    for (int column = 0; column < schema.count(); column++) {
      StatusCode status = decodeText(destination, schema, column);
      if (!status.isOk()) return status;
    }
    return record.position() == HEADER_BYTES + payload
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode prepareRead(int length) {
    if (length <= HEADER_BYTES + TRAILER_BYTES || length > MAXIMUM_RECORD_BYTES) {
      return StatusCode.CORRUPTION;
    }
    StatusCode admitted = reserve(length);
    if (!admitted.isOk()) return admitted;
    record.clear();
    record.limit(length);
    return StatusCode.OK;
  }

  ByteBuffer buffer() { return record; }
  int capacity() { return record == null ? 0 : record.capacity(); }

  void eraseScratch() {
    erase(record, record == null ? 0 : record.position());
  }

  void reset() {
    erase(record, highWater);
    text.clear();
    highWater = 0;
  }

  void close() {
    reset();
    if (record != null && record.capacity() > WARM_RECORD_BYTES) {
      if (budget != null) budget.rollback(record.capacity());
      record = null;
    }
  }

  private StatusCode encodeText(
      SqlBlockRow source, SqlBlockSchema schema, int column) {
    if (!schema.varchar(column) || source.nullValue(column)) {
      record.putShort((short) 0);
      return StatusCode.OK;
    }
    text.set(source, column);
    int lengthPosition = record.position();
    record.putShort((short) 0);
    int encoded = Utf8Text.encode(text, Utf8Text.MAXIMUM_SCALARS, record);
    if (encoded < 0 || encoded > 0xffff) return StatusCode.RESOURCE_EXHAUSTED;
    record.putShort(lengthPosition, (short) encoded);
    return StatusCode.OK;
  }

  private StatusCode reserve(int required) {
    int current = record == null ? 0 : record.capacity();
    if (required <= current) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        current, required, MAXIMUM_RECORD_BYTES, WARM_RECORD_BYTES);
    StatusCode admitted = budget == null ? StatusCode.OK : budget.reserve(capacity);
    if (!admitted.isOk()) return admitted;
    try {
      ByteBuffer grown = ByteBuffer.allocateDirect(capacity);
      ByteBuffer previous = record;
      record = grown;
      erase(previous, highWater);
      if (budget != null && current > 0) budget.rollback(current);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null) budget.rollback(capacity);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static int maximumEncodedBytes(SqlBlockRow source, SqlBlockSchema schema) {
    if (source == null || schema == null || !source.status().isOk()
        || !schema.status().isOk() || source.count() != schema.count()) return -1;
    long bytes = HEADER_BYTES + TRAILER_BYTES
        + ((schema.count() + Long.SIZE - 1L) >>> 6) * Long.BYTES
        + (long) schema.count() * (Long.BYTES + Short.BYTES);
    for (int column = 0; column < schema.count(); column++) {
      if (SqlTypeDescriptor.isWideDecimal(schema.descriptor(column))) {
        bytes += Long.BYTES;
      }
      if (schema.varchar(column) && !source.nullValue(column)) {
        bytes += (long) source.textLength(column) * 4;
      }
    }
    return bytes > MAXIMUM_RECORD_BYTES ? -1 : (int) bytes;
  }

  private StatusCode decodeText(
      SqlBlockRow destination, SqlBlockSchema schema, int column) {
    int encoded = Short.toUnsignedInt(record.getShort());
    if (!schema.varchar(column) || destination.nullValue(column)) {
      return encoded == 0 ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    if (record.position() > record.limit() - TRAILER_BYTES - encoded) {
      return StatusCode.CORRUPTION;
    }
    if (encoded == 0) {
      destination.setTextLength(column, 0);
      return StatusCode.OK;
    }
    int requiredCharacters = Utf8Text.decodedLength(record, record.position(), encoded);
    if (requiredCharacters < 0) return StatusCode.CORRUPTION;
    StatusCode prepared = destination.prepareText(column, requiredCharacters);
    if (!prepared.isOk()) return prepared;
    int characters = Utf8Text.decode(
        record, record.position(), encoded, destination.text(column), 0);
    if (characters < 0) return StatusCode.CORRUPTION;
    destination.setTextLength(column, characters);
    record.position(record.position() + encoded);
    return StatusCode.OK;
  }

  private static long minimumPayloadBytes(SqlBlockSchema schema) {
    if (schema == null || !schema.status().isOk()) return Long.MAX_VALUE;
    long bytes = ((schema.count() + Long.SIZE - 1L) >>> 6) * Long.BYTES
        + (long) schema.count() * (Long.BYTES + Short.BYTES);
    for (int column = 0; column < schema.count(); column++) {
      if (SqlTypeDescriptor.isWideDecimal(schema.descriptor(column))) bytes += Long.BYTES;
    }
    return bytes;
  }

  private int checksum(int offset, int length) {
    checksum.reset();
    for (int index = 0; index < length; index++) checksum.update(record.get(offset + index));
    return (int) checksum.getValue();
  }

  private static void erase(ByteBuffer buffer, int length) {
    if (buffer == null) return;
    buffer.clear();
    for (int index = 0; index < Math.min(length, buffer.capacity()); index++) {
      buffer.put(index, (byte) 0);
    }
    buffer.clear();
  }

  private static final class TextView implements CharSequence {
    private SqlBlockRow row;
    private int column;
    void set(SqlBlockRow source, int sourceColumn) {
      row = source;
      column = sourceColumn;
    }
    void clear() {
      row = null;
      column = 0;
    }
    @Override public int length() { return row.textLength(column); }
    @Override public char charAt(int index) { return row.textCharacter(column, index); }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
