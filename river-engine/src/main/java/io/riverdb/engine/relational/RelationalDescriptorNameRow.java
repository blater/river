package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable structural decoder for one committed catalog-v2 name-map row. */
final class RelationalDescriptorNameRow implements CharSequence {
  static final int MAXIMUM_BYTES = TableSchema.MAXIMUM_NAME_LENGTH * 4;

  private final ByteBuffer bytes = ByteBuffer.allocateDirect(MAXIMUM_BYTES);
  private final char[] decoded = new char[TableSchema.MAXIMUM_NAME_LENGTH];
  private long objectId;
  private int length;
  private int characters;

  StatusCode read(IndexedScanResult candidate) {
    objectId = 0;
    length = 0;
    characters = 0;
    if (candidate == null || !candidate.isAvailable()
        || candidate.keySpace() != RelationalDescriptorKeyspace.NAME_MAP_SPACE
        || !CatalogKeyspace.validObjectHead(candidate.key())) {
      return StatusCode.CORRUPTION;
    }
    return read(candidate.key(), candidate.row());
  }

  StatusCode read(long candidateObjectId, HeapRowResult candidate) {
    objectId = 0;
    length = 0;
    characters = 0;
    if (!CatalogKeyspace.validObjectHead(candidateObjectId) || candidate == null) {
      return StatusCode.CORRUPTION;
    }
    int candidateLength = candidate.length();
    if (candidateLength <= 0 || candidateLength > bytes.capacity()) {
      return StatusCode.CORRUPTION;
    }
    bytes.clear();
    if (!candidate.copyTo(bytes).isOk()) return StatusCode.CORRUPTION;
    bytes.flip();
    int decodedCharacters = Utf8Text.validate(
        bytes, 0, candidateLength, TableSchema.MAXIMUM_NAME_LENGTH);
    if (decodedCharacters <= 0
        || Utf8Text.decode(bytes, 0, candidateLength, decoded, 0) != decodedCharacters) {
      return StatusCode.CORRUPTION;
    }
    objectId = candidateObjectId;
    length = candidateLength;
    characters = decodedCharacters;
    return StatusCode.OK;
  }

  boolean matches(ByteBuffer expected, int expectedLength) {
    return expectedLength == length
        && Utf8Text.compare(expected, 0, expectedLength, bytes, 0, length) == 0;
  }

  long objectId() {
    return objectId;
  }

  ByteBuffer bytes() {
    return bytes;
  }

  int byteLength() {
    return length;
  }

  long hash() {
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < length; index++) {
      hash = (hash ^ Byte.toUnsignedLong(bytes.get(index))) * 0x100000001b3L;
    }
    return hash;
  }

  @Override
  public int length() {
    return characters;
  }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= characters) throw new IndexOutOfBoundsException(index);
    return decoded[index];
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return new String(decoded, start, end - start);
  }
}
