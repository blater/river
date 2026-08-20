package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable owned text scratch for one scalar expression result. */
final class SqlRowTextScratch implements CharSequence {
  private final char[] characters = new char[510];
  private ByteBuffer literalBytes;
  private int length;
  private int highWater;
  private int literalHighWater;

  StatusCode loadBlock(SqlBlockRow source, int column) {
    clear();
    int sourceLength = source.textLength(column);
    if (sourceLength > characters.length) return StatusCode.RESOURCE_EXHAUSTED;
    for (int index = 0; index < sourceLength; index++) {
      characters[index] = source.textCharacter(column, index);
    }
    publish(sourceLength);
    return StatusCode.OK;
  }

  StatusCode loadLiteral(SqlCommand command, long handle) {
    clear();
    int byteLength = command.textByteLength(handle);
    if (literalBytes == null) literalBytes = ByteBuffer.allocate(1_020);
    literalBytes.clear();
    if (byteLength < 0 || byteLength > literalBytes.remaining()
        || command.copyText(handle, literalBytes) != byteLength) {
      clear();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    literalHighWater = byteLength;
    literalBytes.flip();
    if (Utf8Text.validate(
        literalBytes, 0, byteLength, Utf8Text.MAXIMUM_SCALARS) < 0) {
      clear();
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    int decoded = Utf8Text.decode(
        literalBytes, 0, byteLength, characters, 0);
    if (decoded < 0) {
      highWater = characters.length;
      clear();
      return StatusCode.INVALID_DATETIME_FORMAT;
    }
    publish(decoded);
    return StatusCode.OK;
  }

  StatusCode loadRow(
      HeapRowResult source, TableDefinition definition, long handle) {
    int offset = (int) (handle >>> 32);
    int byteLength = (int) handle;
    if (source == null || definition == null || offset < definition.fixedRowBytes()
        || byteLength < 0 || byteLength > 1_020
        || offset > source.length() - byteLength) return StatusCode.CORRUPTION;
    int decoded = Utf8RowText.decode(
        source, offset, byteLength, characters);
    if (decoded < 0) {
      highWater = characters.length;
      clear();
      return StatusCode.CORRUPTION;
    }
    publish(decoded);
    return StatusCode.OK;
  }

  void publish(int textLength) {
    length = textLength;
    highWater = Math.max(highWater, textLength);
  }

  char[] writableCharacters() { return characters; }

  void clear() {
    for (int index = 0; index < highWater; index++) characters[index] = 0;
    highWater = 0;
    length = 0;
    if (literalBytes != null) {
      for (int index = 0; index < literalHighWater; index++) {
        literalBytes.put(index, (byte) 0);
      }
      literalBytes.clear();
    }
    literalHighWater = 0;
  }

  @Override public int length() { return length; }
  @Override public char charAt(int index) { return characters[index]; }
  @Override public CharSequence subSequence(int start, int end) {
    throw new UnsupportedOperationException();
  }
}
