package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8TextArena;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Packed text, names, primitive values, descriptors, and nulls for one response. */
final class ProtocolResponseValues {
  private static final int DESCRIPTOR = 0;
  private static final int TEXT_OFFSET = 1;
  private static final int TEXT_LENGTH = 2;
  private static final int NAME_OFFSET = 3;
  private static final int NAME_LENGTH = 4;
  private final ProtocolResponseColumns columns = new ProtocolResponseColumns();
  private final Utf8TextArena text = new Utf8TextArena();
  private final Utf8TextArena names = new Utf8TextArena();
  private final char[] nameScratch = new char[ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES];

  StatusCode reserve(int count, int textBytes, int nameBytes) {
    if (count < 0 || count > SqlShapeLimits.MAX_RESULT_COLUMNS
        || textBytes < 0 || textBytes > SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES
        || nameBytes < 0 || nameBytes > SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = columns.reserve(count);
    if (status.isOk()) status = text.reserve(
        textBytes, SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES);
    return status.isOk()
        ? names.reserve(nameBytes, SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES) : status;
  }

  void reset(int count) { columns.reset(count); text.reset(); names.reset(); }
  StatusCode beginNulls(int count) { return columns.beginNulls(count); }
  boolean nullWordAt(int word, long value) { return columns.nullWordAt(word, value); }
  void descriptor(int index, int value) { columns.lane(DESCRIPTOR, index, value); }
  void value(int index, long value) { columns.value(index, value); }
  void decimalHigh(int index, long value) { columns.decimalHigh(index, value); }
  long value(int index) { return columns.value(index); }
  long decimalHigh(int index) { return columns.decimalHigh(index); }
  boolean isNull(int index) { return columns.isNull(index); }
  long nullWord(int word) { return columns.nullWord(word); }
  int nullWordCount() { return columns.nullWordCount(); }
  int descriptor(int index) { return columns.lane(DESCRIPTOR, index); }
  int textBytesUsed() { return text.used(); }

  boolean textAt(int index, ByteBuffer source, int offset, int length) {
    StatusCode status = text.append(
        source, offset, length, SqlTypeDescriptor.parameterOne(descriptor(index)));
    if (!status.isOk()) return false;
    columns.lane(TEXT_OFFSET, index, text.lastOffset());
    columns.lane(TEXT_LENGTH, index, text.lastLength());
    return true;
  }

  boolean nameAt(int index, ByteBuffer source, int offset, int length) {
    StatusCode status = names.append(source, offset, length, length);
    if (!status.isOk()) return false;
    columns.lane(NAME_OFFSET, index, names.lastOffset());
    columns.lane(NAME_LENGTH, index, names.lastLength());
    return true;
  }

  int textLength(int index) {
    return text.decodedLength(
        columns.lane(TEXT_OFFSET, index), columns.lane(TEXT_LENGTH, index));
  }

  int textByteLength(int index) { return columns.lane(TEXT_LENGTH, index); }

  int copyText(int index, char[] destination, int offset) {
    return text.copyChars(columns.lane(TEXT_OFFSET, index),
        columns.lane(TEXT_LENGTH, index), destination, offset);
  }

  String name(int index) {
    int length = columns.lane(NAME_LENGTH, index);
    if (length <= 0) return null;
    int chars = names.copyChars(columns.lane(NAME_OFFSET, index), length, nameScratch, 0);
    return chars < 0 ? null : new String(nameScratch, 0, chars);
  }
}
