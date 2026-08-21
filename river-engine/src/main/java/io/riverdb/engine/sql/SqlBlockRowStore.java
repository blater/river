package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Lazy bounded row store and external-sort owner for one block boundary. */
final class SqlBlockRowStore {
  static final int MAXIMUM_ROWS = 65_536;
  static final long MAXIMUM_BYTES = 256L * 1_024 * 1_024;
  private static final int MEMORY_ROWS = 1_024;
  private static final int INITIAL_BYTES = 256 * 1_024;
  private static final int MAXIMUM_MEMORY_BYTES = 4 * 1_024 * 1_024;
  private final SqlBlockRowCodec codec = new SqlBlockRowCodec();
  private final SqlBlockRowIndex index = new SqlBlockRowIndex();
  private ByteBuffer data;
  private SqlBlockSchema schema;
  private FileChannel channel;
  private Path path;
  private long bytes;
  private int rowCount;
  private int next;
  private boolean spilled;

  StatusCode begin(SqlBlockSchema rowSchema, int keyColumn, boolean descendingOrder) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    if (rowSchema == null || keyColumn < -1 || keyColumn >= rowSchema.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    schema = rowSchema;
    index.begin(keyColumn, descendingOrder,
        keyColumn >= 0 && rowSchema.varchar(keyColumn));
    rowCount = 0;
    next = 0;
    bytes = 0;
    spilled = false;
    if (data != null) data.clear();
    return StatusCode.OK;
  }

  StatusCode append(SqlBlockRow source) {
    if (schema == null || source == null || source.count() != schema.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (rowCount >= MAXIMUM_ROWS) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = index.ensure(rowCount + 1, retainedWithoutIndex());
    if (!status.isOk()) return status;
    int priorKeyText = index.textPosition();
    status = codec.encode(source, schema, rowCount);
    if (status.isOk()) status = index.captureKey(
        source, rowCount, retainedWithoutIndex());
    if (!status.isOk()) {
      index.rollbackText(priorKeyText);
      codec.eraseScratch();
      index.eraseSlot(rowCount);
      return status;
    }
    int bytesToWrite = codec.buffer().remaining();
    long ownedBytes = retainedBytes(bytesToWrite);
    if (ownedBytes > MAXIMUM_BYTES) {
      index.rollbackText(priorKeyText);
      index.eraseSlot(rowCount);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!spilled && (rowCount >= MEMORY_ROWS
        || !ensureData(bytesToWrite))) {
      status = beginSpill();
    }
    if (!status.isOk()) {
      index.rollbackText(priorKeyText);
      index.eraseSlot(rowCount);
      return status;
    }
    if (retainedBytes(bytesToWrite) > MAXIMUM_BYTES) {
      index.rollbackText(priorKeyText);
      index.eraseSlot(rowCount);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    index.setRecord(rowCount, bytes, bytesToWrite);
    if (spilled) status = write(codec.buffer(), bytes); else data.put(codec.buffer());
    if (!status.isOk()) {
      index.rollbackText(priorKeyText);
      index.eraseSlot(rowCount);
      return status;
    }
    bytes += bytesToWrite;
    rowCount++;
    return StatusCode.OK;
  }

  StatusCode finish() {
    if (schema == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    index.finish(rowCount);
    next = 0;
    return StatusCode.OK;
  }

  StatusCode next(SqlBlockRow destination) {
    if (schema == null || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (next >= rowCount) return StatusCode.CONFLICT;
    int stored = index.stored(next++);
    StatusCode status = readRecord(stored);
    return status.isOk() ? codec.decode(destination, schema, stored) : status;
  }

  StatusCode readAt(int position, SqlBlockRow destination) {
    if (schema == null || destination == null
        || position < 0 || position >= rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int stored = index.stored(position);
    StatusCode status = readRecord(stored);
    return status.isOk() ? codec.decode(destination, schema, stored) : status;
  }

  void rewind() { next = 0; }

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
      index.close(rowCount);
      erase(data);
      codec.reset();
      if (data != null && data.capacity() > INITIAL_BYTES) data = null;
      schema = null;
      rowCount = 0;
      next = 0;
      bytes = 0;
      spilled = false;
    }
    return status;
  }

  private StatusCode readRecord(int stored) {
    int length = index.length(stored);
    StatusCode status = codec.prepareRead(length);
    if (!status.isOk()) return status;
    ByteBuffer record = codec.buffer();
    if (!spilled) {
      int offset = Math.toIntExact(index.offset(stored));
      for (int index = 0; index < length; index++) {
        record.put(index, data.get(offset + index));
      }
      record.position(0);
      return StatusCode.OK;
    }
    return read(record, index.offset(stored));
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
      long maximum = maximumDataBytes();
      if (required > maximum) return false;
      data = ByteBuffer.allocateDirect((int) Math.min(INITIAL_BYTES, maximum));
    }
    if (data.remaining() >= required) return true;
    int needed = data.position() + required;
    if (needed > MAXIMUM_MEMORY_BYTES) return false;
    long maximum = Math.min(MAXIMUM_MEMORY_BYTES, maximumDataBytes());
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

  private long retainedWithoutIndex() {
    return bytes
        + (data == null ? 0 : data.capacity())
        + codec.capacity();
  }

  private long retainedBytes(int appendedRecordBytes) {
    return bytes + appendedRecordBytes
        + (data == null ? 0 : data.capacity())
        + codec.capacity()
        + index.retainedBytes();
  }

  private long maximumDataBytes() {
    return Math.max(
        data == null ? 0 : data.capacity(),
        MAXIMUM_BYTES - bytes - index.retainedBytes() - codec.capacity());
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

}
