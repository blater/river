package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.relational.TableSchema;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/** Lazy bounded row store and external-sort owner for one block boundary. */
final class SqlBlockRowStore {
  static final int MAXIMUM_ROWS = 65_536;
  static final long MAXIMUM_BYTES = 256L * 1_024 * 1_024;
  private static final int MEMORY_ROWS = 1_024;
  private static final int INITIAL_BYTES = 256 * 1_024;
  private static final int MAXIMUM_MEMORY_BYTES = 4 * 1_024 * 1_024;
  private static final int MAXIMUM_RECORD_BYTES = 20 * 1_024;
  private static final int HEADER_BYTES = Integer.BYTES + Long.BYTES;
  private static final int TRAILER_BYTES = Integer.BYTES;

  private final TextView textView = new TextView();
  private final CRC32C checksum = new CRC32C();
  private ByteBuffer record;
  private long[] offsets;
  private int[] lengths;
  private int[] order;
  private long[] keys;
  private boolean[] keyNulls;
  private int[] keyTextOffsets;
  private short[] keyTextLengths;
  private ByteBuffer data;
  private ByteBuffer keyText;
  private SqlBlockSchema schema;
  private FileChannel channel;
  private Path path;
  private long bytes;
  private int recordHighWater;
  private int rowCount;
  private int next;
  private int sortKey = -1;
  private boolean descending;
  private boolean textKey;
  private boolean spilled;

  StatusCode begin(SqlBlockSchema rowSchema, int keyColumn, boolean descendingOrder) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    if (rowSchema == null || keyColumn < -1 || keyColumn >= rowSchema.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    schema = rowSchema;
    sortKey = keyColumn;
    descending = descendingOrder;
    textKey = keyColumn >= 0 && rowSchema.varchar(keyColumn);
    rowCount = 0;
    next = 0;
    bytes = 0;
    spilled = false;
    if (data != null) data.clear();
    if (keyText != null) keyText.clear();
    return StatusCode.OK;
  }

  StatusCode append(SqlBlockRow source) {
    if (schema == null || source == null || source.count() != schema.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (rowCount >= MAXIMUM_ROWS) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = ensureIndexStorage(rowCount + 1);
    if (!status.isOk()) return status;
    int priorKeyText = keyText == null ? 0 : keyText.position();
    status = encode(source, rowCount);
    textView.clear();
    if (!status.isOk()) {
      eraseTail(keyText, priorKeyText);
      erase(record, record == null ? 0 : record.position());
      eraseIndex(rowCount);
      return status;
    }
    int bytesToWrite = record.remaining();
    long ownedBytes = retainedBytes(bytesToWrite);
    if (ownedBytes > MAXIMUM_BYTES) {
      eraseTail(keyText, priorKeyText);
      eraseIndex(rowCount);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!spilled && (rowCount >= MEMORY_ROWS
        || !ensureData(bytesToWrite))) {
      status = beginSpill();
    }
    if (!status.isOk()) {
      eraseTail(keyText, priorKeyText);
      eraseIndex(rowCount);
      return status;
    }
    if (retainedBytes(bytesToWrite) > MAXIMUM_BYTES) {
      eraseTail(keyText, priorKeyText);
      eraseIndex(rowCount);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    offsets[rowCount] = bytes;
    lengths[rowCount] = bytesToWrite;
    order[rowCount] = rowCount;
    if (spilled) status = write(record, bytes); else data.put(record);
    if (!status.isOk()) {
      eraseTail(keyText, priorKeyText);
      eraseIndex(rowCount);
      return status;
    }
    bytes += bytesToWrite;
    rowCount++;
    return StatusCode.OK;
  }

  StatusCode finish() {
    if (schema == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (sortKey >= 0) sort();
    next = 0;
    return StatusCode.OK;
  }

  StatusCode next(SqlBlockRow destination) {
    if (schema == null || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (next >= rowCount) return StatusCode.CONFLICT;
    int stored = order[next++];
    StatusCode status = readRecord(stored);
    return status.isOk() ? decode(destination, stored) : status;
  }

  int rowCount() { return rowCount; }
  boolean spilled() { return spilled; }
  boolean hasResources() { return schema != null || channel != null || path != null; }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException failure) {
        status = StatusCode.IO_FAILURE;
      }
      if (!channel.isOpen()) channel = null;
    }
    if (status.isOk() && path != null) {
      try {
        Files.deleteIfExists(path);
        path = null;
      } catch (IOException failure) {
        status = StatusCode.IO_FAILURE;
      }
    }
    if (status.isOk()) {
      eraseIndexes(rowCount);
      erase(data);
      erase(keyText);
      erase(record, recordHighWater);
      textView.clear();
      schema = null;
      rowCount = 0;
      next = 0;
      bytes = 0;
      spilled = false;
      recordHighWater = 0;
    }
    return status;
  }

  private StatusCode encode(SqlBlockRow source, int ordinal) {
    if (record == null) record = ByteBuffer.allocateDirect(MAXIMUM_RECORD_BYTES);
    record.clear();
    record.position(HEADER_BYTES);
    record.putLong(source.nullMask());
    for (int column = 0; column < schema.count(); column++) {
      record.putLong(schema.varchar(column) ? 0 : source.value(column));
    }
    for (int column = 0; column < schema.count(); column++) {
      if (!schema.varchar(column) || source.nullValue(column)) {
        record.putShort((short) 0);
        continue;
      }
      textView.set(source, column);
      int lengthPosition = record.position();
      record.putShort((short) 0);
      int encoded = Utf8Text.encode(
          textView, Utf8Text.MAXIMUM_SCALARS, record);
      if (encoded < 0 || encoded > Short.MAX_VALUE) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      record.putShort(lengthPosition, (short) encoded);
    }
    int payloadBytes = record.position() - HEADER_BYTES;
    record.putInt(0, payloadBytes);
    record.putLong(Integer.BYTES, ordinal);
    record.putInt(checksum(HEADER_BYTES, payloadBytes));
    record.flip();
    recordHighWater = Math.max(recordHighWater, record.limit());
    if (sortKey >= 0) {
      keyNulls[ordinal] = source.nullValue(sortKey);
      if (!keyNulls[ordinal] && textKey) {
        StatusCode status = appendTextKey(source, ordinal);
        if (!status.isOk()) return status;
      } else if (!keyNulls[ordinal]) {
        keys[ordinal] = source.value(sortKey);
      }
    }
    return StatusCode.OK;
  }

  private StatusCode appendTextKey(SqlBlockRow source, int ordinal) {
    textView.set(source, sortKey);
    int bytesNeeded = Utf8Text.encodedLength(textView, Utf8Text.MAXIMUM_SCALARS);
    if (bytesNeeded < 0 || bytesNeeded > Short.MAX_VALUE
        || !ensureKeyText(bytesNeeded)) return StatusCode.RESOURCE_EXHAUSTED;
    keyTextOffsets[ordinal] = keyText.position();
    keyTextLengths[ordinal] = (short) bytesNeeded;
    return Utf8Text.encode(textView, Utf8Text.MAXIMUM_SCALARS, keyText) == bytesNeeded
        ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode decode(SqlBlockRow destination, int ordinal) {
    int payloadBytes = record.getInt(0);
    if (payloadBytes <= 0
        || record.getLong(Integer.BYTES) != ordinal
        || record.limit() != HEADER_BYTES + payloadBytes + TRAILER_BYTES
        || record.getInt(HEADER_BYTES + payloadBytes)
            != checksum(HEADER_BYTES, payloadBytes)) {
      return StatusCode.CORRUPTION;
    }
    record.position(HEADER_BYTES);
    long nullMask = record.getLong();
    long validNullMask = schema.count() == Long.SIZE
        ? -1L : (1L << schema.count()) - 1;
    if ((nullMask & ~validNullMask) != 0) return StatusCode.CORRUPTION;
    destination.reset(schema.count());
    for (int column = 0; column < schema.count(); column++) {
      long value = record.getLong();
      if ((nullMask & 1L << column) != 0) destination.setNull(column);
      else destination.setValue(column, value);
    }
    for (int column = 0; column < schema.count(); column++) {
      int encoded = Short.toUnsignedInt(record.getShort());
      if (!schema.varchar(column) || destination.nullValue(column)) {
        if (encoded != 0) return StatusCode.CORRUPTION;
        continue;
      }
      int characters = Utf8Text.decode(
          record, record.position(), encoded, destination.text(column), 0);
      if (characters < 0) return StatusCode.CORRUPTION;
      destination.setText(column, destination.text(column), 0, characters);
      record.position(record.position() + encoded);
    }
    return record.position() == HEADER_BYTES + payloadBytes
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode readRecord(int stored) {
    int length = lengths[stored];
    if (length <= HEADER_BYTES + TRAILER_BYTES || length > record.capacity()) {
      return StatusCode.CORRUPTION;
    }
    record.clear();
    record.limit(length);
    if (!spilled) {
      int offset = Math.toIntExact(offsets[stored]);
      for (int index = 0; index < length; index++) {
        record.put(index, data.get(offset + index));
      }
      record.position(0);
      return StatusCode.OK;
    }
    return read(record, offsets[stored]);
  }

  private void sort() {
    for (int root = rowCount / 2 - 1; root >= 0; root--) sift(root, rowCount);
    for (int end = rowCount - 1; end > 0; end--) {
      int swap = order[0];
      order[0] = order[end];
      order[end] = swap;
      sift(0, end);
    }
  }

  private void sift(int root, int length) {
    int current = root;
    while (current * 2 + 1 < length) {
      int child = current * 2 + 1;
      if (child + 1 < length && compare(order[child], order[child + 1]) < 0) child++;
      if (compare(order[current], order[child]) >= 0) return;
      int swap = order[current];
      order[current] = order[child];
      order[child] = swap;
      current = child;
    }
  }

  private int compare(int left, int right) {
    int compared;
    if (keyNulls[left] != keyNulls[right]) compared = keyNulls[left] ? -1 : 1;
    else if (keyNulls[left]) compared = 0;
    else if (textKey) compared = compareText(left, right);
    else compared = Long.compare(keys[left], keys[right]);
    if (compared != 0) return descending ? -compared : compared;
    return Integer.compare(left, right);
  }

  private int compareText(int left, int right) {
    int leftLength = Short.toUnsignedInt(keyTextLengths[left]);
    int rightLength = Short.toUnsignedInt(keyTextLengths[right]);
    int common = Math.min(leftLength, rightLength);
    int leftOffset = keyTextOffsets[left];
    int rightOffset = keyTextOffsets[right];
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(keyText.get(leftOffset + index)),
          Byte.toUnsignedInt(keyText.get(rightOffset + index)));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftLength, rightLength);
  }

  private StatusCode beginSpill() {
    try {
      path = Files.createTempFile("river-block-", ".rows");
      channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
      data.flip();
      StatusCode status = write(data, 0);
      erase(data, data.limit());
      spilled = status.isOk();
      return status;
    } catch (IOException failure) {
      channel = null;
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode write(ByteBuffer source, long offset) {
    try {
      long position = offset;
      while (source.hasRemaining()) {
        int written = channel.write(source, position);
        if (written <= 0) return StatusCode.IO_FAILURE;
        position += written;
      }
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode read(ByteBuffer target, long offset) {
    try {
      long position = offset;
      while (target.hasRemaining()) {
        int count = channel.read(target, position);
        if (count <= 0) return StatusCode.CORRUPTION;
        position += count;
      }
      target.flip();
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private boolean ensureData(int required) {
    if (data == null) {
      long maximum = maximumArenaBytes(null, keyText);
      if (required > maximum) return false;
      data = ByteBuffer.allocateDirect((int) Math.min(INITIAL_BYTES, maximum));
    }
    if (data.remaining() >= required) return true;
    int needed = data.position() + required;
    if (needed > MAXIMUM_MEMORY_BYTES) return false;
    long maximum = Math.min(MAXIMUM_MEMORY_BYTES, maximumArenaBytes(data, keyText));
    if (needed > maximum) return false;
    int capacity = data.capacity();
    while (capacity < needed) capacity = (int) Math.min(maximum, capacity * 2L);
    ByteBuffer grown = ByteBuffer.allocateDirect(capacity);
    int used = data.position();
    data.flip();
    grown.put(data);
    erase(data, used);
    data = grown;
    return true;
  }

  private boolean ensureKeyText(int required) {
    if (keyText == null) {
      long maximum = maximumArenaBytes(null, data);
      if (required > maximum) return false;
      keyText = ByteBuffer.allocateDirect((int) Math.min(64 * 1_024, maximum));
    }
    if (keyText.remaining() >= required) return true;
    int needed = keyText.position() + required;
    long maximum = maximumArenaBytes(keyText, data);
    if (needed > maximum) return false;
    int capacity = keyText.capacity();
    while (capacity < needed) capacity = (int) Math.min(maximum, capacity * 2L);
    ByteBuffer grown = ByteBuffer.allocateDirect(capacity);
    int used = keyText.position();
    keyText.flip();
    grown.put(keyText);
    erase(keyText, used);
    keyText = grown;
    return true;
  }

  private int checksum(int offset, int length) {
    checksum.reset();
    for (int index = 0; index < length; index++) checksum.update(record.get(offset + index));
    return (int) checksum.getValue();
  }

  private StatusCode ensureIndexStorage(int required) {
    if (offsets != null && offsets.length >= required
        && (sortKey < 0 || keys != null && keys.length >= required)
        && (!textKey || keyTextOffsets != null && keyTextOffsets.length >= required)) {
      return StatusCode.OK;
    }
    int capacity = offsets == null ? MEMORY_ROWS : offsets.length;
    while (capacity < required) capacity = Math.min(MAXIMUM_ROWS, capacity * 2);
    if (retainedWithoutIndexes() + prospectiveIndexBytes(capacity)
        > MAXIMUM_BYTES) return StatusCode.RESOURCE_EXHAUSTED;
    offsets = grow(offsets, capacity);
    lengths = grow(lengths, capacity);
    order = grow(order, capacity);
    if (sortKey >= 0) {
      keys = grow(keys, capacity);
      keyNulls = grow(keyNulls, capacity);
    }
    if (textKey) {
      keyTextOffsets = grow(keyTextOffsets, capacity);
      keyTextLengths = grow(keyTextLengths, capacity);
    }
    return StatusCode.OK;
  }

  private long retainedWithoutIndexes() {
    return bytes
        + (data == null ? 0 : data.capacity())
        + (keyText == null ? 0 : keyText.capacity())
        + (record == null ? 0 : record.capacity());
  }

  private long prospectiveIndexBytes(int capacity) {
    long retained = (long) capacity * (Long.BYTES + Integer.BYTES + Integer.BYTES);
    int keyCapacity = sortKey >= 0
        ? capacity : keys == null ? 0 : keys.length;
    int nullCapacity = sortKey >= 0
        ? capacity : keyNulls == null ? 0 : keyNulls.length;
    int textOffsetCapacity = textKey
        ? capacity : keyTextOffsets == null ? 0 : keyTextOffsets.length;
    int textLengthCapacity = textKey
        ? capacity : keyTextLengths == null ? 0 : keyTextLengths.length;
    return retained
        + (long) keyCapacity * Long.BYTES
        + nullCapacity
        + (long) textOffsetCapacity * Integer.BYTES
        + (long) textLengthCapacity * Short.BYTES;
  }

  private long retainedBytes(int appendedRecordBytes) {
    return bytes + appendedRecordBytes
        + (keyText == null ? 0 : keyText.capacity())
        + (data == null ? 0 : data.capacity())
        + (record == null ? 0 : record.capacity())
        + retainedIndexBytes();
  }

  private long maximumArenaBytes(ByteBuffer arena, ByteBuffer other) {
    long fixed = bytes
        + retainedIndexBytes()
        + (record == null ? 0 : record.capacity())
        + (other == null ? 0 : other.capacity());
    return Math.max(arena == null ? 0 : arena.capacity(), MAXIMUM_BYTES - fixed);
  }

  private long retainedIndexBytes() {
    long retained = offsets == null ? 0
        : (long) offsets.length * Long.BYTES
            + (long) lengths.length * Integer.BYTES
            + (long) order.length * Integer.BYTES;
    if (keys != null) retained += (long) keys.length * Long.BYTES;
    if (keyNulls != null) retained += keyNulls.length;
    if (keyTextOffsets != null) retained += (long) keyTextOffsets.length * Integer.BYTES;
    if (keyTextLengths != null) retained += (long) keyTextLengths.length * Short.BYTES;
    return retained;
  }

  private void eraseIndexes(int used) {
    for (int index = 0; index < used; index++) {
      eraseIndex(index);
    }
  }

  private void eraseIndex(int index) {
    offsets[index] = 0;
    lengths[index] = 0;
    order[index] = 0;
    if (keys != null) keys[index] = 0;
    if (keyNulls != null) keyNulls[index] = false;
    if (keyTextOffsets != null) keyTextOffsets[index] = 0;
    if (keyTextLengths != null) keyTextLengths[index] = 0;
  }

  private static void erase(ByteBuffer buffer) {
    if (buffer == null) return;
    erase(buffer, buffer.position());
  }

  private static void erase(ByteBuffer buffer, int length) {
    if (buffer == null) return;
    buffer.clear();
    for (int index = 0; index < Math.min(length, buffer.capacity()); index++) {
      buffer.put(index, (byte) 0);
    }
    buffer.clear();
  }

  private static void eraseTail(ByteBuffer buffer, int offset) {
    if (buffer == null) return;
    int end = buffer.position();
    for (int index = offset; index < end; index++) buffer.put(index, (byte) 0);
    buffer.position(offset);
  }

  private static long[] grow(long[] source, int capacity) {
    long[] grown = new long[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, 0);
    }
    return grown;
  }

  private static int[] grow(int[] source, int capacity) {
    int[] grown = new int[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, 0);
    }
    return grown;
  }

  private static short[] grow(short[] source, int capacity) {
    short[] grown = new short[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, (short) 0);
    }
    return grown;
  }

  private static boolean[] grow(boolean[] source, int capacity) {
    boolean[] grown = new boolean[capacity];
    if (source != null) {
      System.arraycopy(source, 0, grown, 0, source.length);
      java.util.Arrays.fill(source, false);
    }
    return grown;
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
