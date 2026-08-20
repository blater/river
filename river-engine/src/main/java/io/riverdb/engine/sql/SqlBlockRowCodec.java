package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Encodes and validates one canonical block-store row record. */
final class SqlBlockRowCodec {
  static final int MAXIMUM_RECORD_BYTES = 20 * 1_024;
  static final int HEADER_BYTES = Integer.BYTES + Long.BYTES;
  static final int TRAILER_BYTES = Integer.BYTES;

  private final TextView text = new TextView();
  private final CRC32C checksum = new CRC32C();
  private ByteBuffer record;
  private int highWater;

  StatusCode encode(SqlBlockRow source, SqlBlockSchema schema, int ordinal) {
    if (record == null) record = ByteBuffer.allocateDirect(MAXIMUM_RECORD_BYTES);
    record.clear();
    record.position(HEADER_BYTES);
    record.putLong(source.nullMask());
    for (int column = 0; column < schema.count(); column++) {
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

  StatusCode decode(SqlBlockRow destination, SqlBlockSchema schema, int ordinal) {
    int payload = record.getInt(0);
    if (payload <= 0
        || record.getLong(Integer.BYTES) != ordinal
        || record.limit() != HEADER_BYTES + payload + TRAILER_BYTES
        || record.getInt(HEADER_BYTES + payload) != checksum(HEADER_BYTES, payload)) {
      return StatusCode.CORRUPTION;
    }
    record.position(HEADER_BYTES);
    long nullMask = record.getLong();
    long validMask = schema.count() == Long.SIZE ? -1L : (1L << schema.count()) - 1;
    if ((nullMask & ~validMask) != 0) return StatusCode.CORRUPTION;
    destination.reset(schema.count());
    for (int column = 0; column < schema.count(); column++) {
      long value = record.getLong();
      if ((nullMask & 1L << column) != 0) destination.setNull(column);
      else destination.setValue(column, value);
    }
    for (int column = 0; column < schema.count(); column++) {
      StatusCode status = decodeText(destination, schema, column);
      if (!status.isOk()) return status;
    }
    return record.position() == HEADER_BYTES + payload
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode prepareRead(int length) {
    if (record == null || length <= HEADER_BYTES + TRAILER_BYTES
        || length > record.capacity()) return StatusCode.CORRUPTION;
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
    if (encoded < 0 || encoded > Short.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    record.putShort(lengthPosition, (short) encoded);
    return StatusCode.OK;
  }

  private StatusCode decodeText(
      SqlBlockRow destination, SqlBlockSchema schema, int column) {
    int encoded = Short.toUnsignedInt(record.getShort());
    if (!schema.varchar(column) || destination.nullValue(column)) {
      return encoded == 0 ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    int characters = Utf8Text.decode(
        record, record.position(), encoded, destination.text(column), 0);
    if (characters < 0) return StatusCode.CORRUPTION;
    destination.setText(column, destination.text(column), 0, characters);
    record.position(record.position() + encoded);
    return StatusCode.OK;
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
