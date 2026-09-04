package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable owned text scratch for one scalar expression result. */
final class SqlRowTextScratch implements CharSequence {
  private char[] characters =
      new char[LocalTemporalCast.MAXIMUM_TEXT_CHARACTERS];
  private ByteBuffer literalBytes;
  private int length;
  private int highWater;
  private int literalHighWater;

  StatusCode loadBlock(SqlBlockRow source, int column) {
    clear();
    int sourceLength = source.textLength(column);
    StatusCode capacity = reserveCharacters(sourceLength);
    if (!capacity.isOk()) return capacity;
    for (int index = 0; index < sourceLength; index++) {
      characters[index] = source.textCharacter(column, index);
    }
    publish(sourceLength);
    return StatusCode.OK;
  }

  StatusCode loadValueBuffer(SqlValueBuffer source, int column) {
    clear();
    StatusCode capacity = reserveCharacters(source.textByteLengthAt(column));
    if (!capacity.isOk()) return capacity;
    int copied = source.copyTextChars(column, characters, 0);
    if (copied < 0 || copied > characters.length) {
      clear();
      return StatusCode.CORRUPTION;
    }
    publish(copied);
    return StatusCode.OK;
  }

  StatusCode loadLiteral(SqlCommand command, long handle) {
    clear();
    int byteLength = command.textByteLength(handle);
    StatusCode capacity = reserveLiteralBytes(byteLength);
    if (!capacity.isOk()) return capacity;
    capacity = reserveCharacters(byteLength);
    if (!capacity.isOk()) return capacity;
    literalBytes.clear();
    if (command.copyText(handle, literalBytes) != byteLength) {
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
        || byteLength < 0
        || offset > source.length() - byteLength) return StatusCode.CORRUPTION;
    StatusCode capacity = reserveCharacters(byteLength);
    if (!capacity.isOk()) return capacity;
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

  private StatusCode reserveCharacters(int required) {
    if (required < 0) return StatusCode.CORRUPTION;
    if (required <= characters.length) return StatusCode.OK;
    try {
      characters = new char[required];
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserveLiteralBytes(int required) {
    if (required < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (literalBytes != null && required <= literalBytes.capacity()) return StatusCode.OK;
    try {
      literalBytes = ByteBuffer.allocate(required);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
