package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fixed-frame cache over one immutable sparse checkpoint generation. */
final class CheckpointVersionPageCache {
  private static final int FRAME_COUNT = 8;
  private final Frame[] frames = new Frame[FRAME_COUNT];
  private final IoResult io = new IoResult();
  private final CheckpointChecksum checksum = new CheckpointChecksum();
  private DurableFile file;
  private long[] pageIds;
  private long[] offsets;
  private int count;
  private long databaseHigh;
  private long databaseLow;
  private long walGeneration;
  private long checkpointId;
  private long commitSequence;
  private long rowCount;
  private long clock;
  private StatusCode status = StatusCode.OK;

  StatusCode bind(
      DurableFile source, long[] ids, long[] fileOffsets, int pageCount,
      long databaseHighBits, long databaseLowBits, long walGenerationValue,
      long generation, long committedAt, long horizon) {
    if (source == null || ids == null || fileOffsets == null || pageCount <= 0
        || pageCount > ids.length || pageCount > fileOffsets.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    close();
    file = source;
    pageIds = ids;
    offsets = fileOffsets;
    count = pageCount;
    databaseHigh = databaseHighBits;
    databaseLow = databaseLowBits;
    walGeneration = walGenerationValue;
    checkpointId = generation;
    commitSequence = committedAt;
    rowCount = horizon;
    status = StatusCode.OK;
    return StatusCode.OK;
  }

  ByteBuffer page(long pageId) {
    int pageIndex = find(pageId);
    if (pageIndex < 0) return null;
    Frame cached = cached(pageId);
    if (cached != null) return cached.bytes;
    Frame frame = reserve();
    if (frame == null) return null;
    frame.bytes.clear();
    status = file.read(offsets[pageIndex], frame.bytes, io);
    if (!status.isOk() || io.bytesTransferred() != CheckpointVersionFormat.SEGMENT_BYTES) {
      status = status.isOk() ? StatusCode.IO_FAILURE : status;
      return null;
    }
    if (!valid(frame.bytes, pageId)) {
      status = StatusCode.CORRUPTION;
      return null;
    }
    frame.pageId = pageId;
    frame.access = ++clock;
    return frame.bytes;
  }

  boolean contains(long pageId) { return find(pageId) >= 0; }
  boolean available() { return file != null; }
  StatusCode status() { return status; }
  int count() { return count; }
  long pageId(int index) { return index >= 0 && index < count ? pageIds[index] : -1; }

  void close() {
    if (file != null) file.close();
    file = null;
    pageIds = null;
    offsets = null;
    count = 0;
    clock = 0;
    for (Frame frame : frames) {
      if (frame != null) frame.pageId = -1;
    }
  }

  private boolean valid(ByteBuffer bytes, long pageId) {
    int checksumOffset = CheckpointVersionFormat.SEGMENT_BYTES - 8;
    int stored = bytes.getInt(checksumOffset);
    return bytes.getLong(0) == CheckpointVersionFormat.SEGMENT_MAGIC
        && bytes.getInt(8) == CheckpointVersionFormat.VERSION
        && bytes.getInt(12) == CheckpointVersionFormat.SEGMENT_HEADER_BYTES
        && bytes.getLong(16) == checkpointId && bytes.getLong(24) == pageId
        && bytes.getLong(32) == rowCount
        && bytes.getInt(40) == CheckpointVersionFormat.PAGE_ROWS
        && bytes.getInt(44) == 0
        && bytes.getLong(48) == databaseHigh && bytes.getLong(56) == databaseLow
        && bytes.getLong(64) == walGeneration && bytes.getLong(72) == commitSequence
        && bytes.getInt(checksumOffset + 4) == ~stored
        && checksum.value(bytes, CheckpointVersionFormat.SEGMENT_BYTES) == stored;
  }

  private int find(long pageId) {
    int low = 0;
    int high = count - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      long candidate = pageIds[middle];
      if (candidate < pageId) low = middle + 1;
      else if (candidate > pageId) high = middle - 1;
      else return middle;
    }
    return -1;
  }

  private Frame cached(long pageId) {
    for (Frame frame : frames) {
      if (frame != null && frame.pageId == pageId) {
        frame.access = ++clock;
        status = StatusCode.OK;
        return frame;
      }
    }
    return null;
  }

  private Frame reserve() {
    int slot = 0;
    long oldest = Long.MAX_VALUE;
    for (int index = 0; index < frames.length; index++) {
      if (frames[index] == null) {
        try {
          return frames[index] = new Frame();
        } catch (OutOfMemoryError exhausted) {
          status = StatusCode.RESOURCE_EXHAUSTED;
          return null;
        }
      }
      if (frames[index].access < oldest) {
        oldest = frames[index].access;
        slot = index;
      }
    }
    frames[slot].pageId = -1;
    return frames[slot];
  }

  private static final class Frame {
    private final ByteBuffer bytes = ByteBuffer
        .allocateDirect(CheckpointVersionFormat.SEGMENT_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    private long pageId = -1;
    private long access;
  }
}
