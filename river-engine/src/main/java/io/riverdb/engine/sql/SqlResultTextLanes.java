package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.text.Utf8TextArena;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Packed text payload and reusable UTF-8 decode scratch for result lanes. */
final class SqlResultTextLanes {
  private final Utf8TextArena arena = new Utf8TextArena();
  private char[] scratch = new char[0];
  private int[] offsets = new int[0];
  private int[] lengths = new int[0];

  StatusCode reserve(int columns, int bytes, int characters) {
    StatusCode status = reserveArrays(columns);
    if (status.isOk()) status = arena.reserve(bytes, SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES);
    return status.isOk() ? reserveScratch(characters) : status;
  }

  StatusCode set(int index, int descriptor, char[] source, int offset, int length) {
    if (source == null || offset < 0 || length < 0 || offset > source.length - length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = arena.append(
        source, offset, length, SqlTypeDescriptor.parameterOne(descriptor));
    if (status.isOk()) publish(index);
    return status;
  }

  StatusCode setUtf8(
      int index, int descriptor, HeapRowResult source, int offset, int length) {
    if (source == null || offset < 0 || length < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int characters = Utf8RowText.decode(source, offset, length, scratch);
    return characters < 0
        ? StatusCode.CORRUPTION : set(index, descriptor, scratch, 0, characters);
  }

  StatusCode setUtf8(
      int index, int descriptor, ByteBuffer source, int offset, int length) {
    if (source == null || offset < 0 || length < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int characters = io.riverdb.base.text.Utf8Text.decode(source, offset, length, scratch, 0);
    return characters < 0
        ? StatusCode.CORRUPTION : set(index, descriptor, scratch, 0, characters);
  }

  void clear(int count) {
    for (int index = 0; index < count; index++) {
      offsets[index] = 0;
      lengths[index] = 0;
    }
    arena.reset();
  }

  void clearLane(int index) { offsets[index] = 0; lengths[index] = 0; }
  int byteLength(int index) { return lengths[index]; }
  int length(int index) { return arena.copyChars(offsets[index], lengths[index], scratch, 0); }
  int copy(int index, char[] destination, int offset) {
    return arena.copyChars(offsets[index], lengths[index], destination, offset);
  }
  char character(int index, int character) {
    int count = length(index);
    return character >= 0 && character < count ? scratch[character] : 0;
  }

  private void publish(int index) {
    offsets[index] = arena.lastOffset();
    lengths[index] = arena.lastLength();
  }

  private StatusCode reserveArrays(int columns) {
    if (columns <= offsets.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        offsets.length, columns, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    try {
      int[] nextOffsets = new int[capacity];
      int[] nextLengths = new int[capacity];
      System.arraycopy(offsets, 0, nextOffsets, 0, offsets.length);
      System.arraycopy(lengths, 0, nextLengths, 0, lengths.length);
      offsets = nextOffsets;
      lengths = nextLengths;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserveScratch(int characters) {
    if (characters <= scratch.length) return StatusCode.OK;
    int maximum = Utf8Text.MAXIMUM_UTF16_CODE_UNITS;
    int capacity = BoundedArrayGrowth.capacity(scratch.length, characters, maximum, 8);
    try {
      scratch = new char[capacity];
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
