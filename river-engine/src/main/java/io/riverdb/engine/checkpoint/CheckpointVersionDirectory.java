package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import java.nio.ByteBuffer;

/** Reads validated records from one sparse checkpoint generation. */
final class CheckpointVersionDirectory {
  private final CheckpointVersionPageCache pages = new CheckpointVersionPageCache();

  StatusCode bind(
      DurableFile file, long[] pageIds, long[] offsets, int count,
      long databaseHigh, long databaseLow, long walGeneration,
      long checkpointId, long commitSequence, long rowCount) {
    return pages.bind(
        file, pageIds, offsets, count, databaseHigh, databaseLow, walGeneration,
        checkpointId, commitSequence, rowCount);
  }

  StatusCode read(
      long rowId, long defaultCommit, CheckpointVersionResult result) {
    long pageId = (rowId - 1) >>> CheckpointVersionFormat.PAGE_SHIFT;
    if (!pages.contains(pageId)) {
      result.set(defaultCommit, 0, false);
      return StatusCode.OK;
    }
    ByteBuffer page = pages.page(pageId);
    if (page == null) return pages.status();
    int offset = CheckpointVersionFormat.SEGMENT_HEADER_BYTES
        + ((int) (rowId - 1) & CheckpointVersionFormat.PAGE_MASK)
        * CheckpointVersionFormat.RECORD_BYTES;
    long committedAt = page.getLong(offset);
    long previous = page.getLong(offset + Long.BYTES);
    long flags = page.getLong(offset + Long.BYTES * 2);
    if (committedAt == 0 && previous == 0 && flags == 0) {
      result.set(defaultCommit, 0, false);
      return StatusCode.OK;
    }
    if (committedAt <= 0 || committedAt > defaultCommit || previous < 0
        || previous >= rowId || flags != 0 && flags != 1) {
      return StatusCode.CORRUPTION;
    }
    result.set(committedAt, previous, flags == 1);
    return StatusCode.OK;
  }

  boolean available() { return pages.available(); }
  int count() { return pages.count(); }
  long pageId(int index) { return pages.pageId(index); }
  void close() { pages.close(); }
}
