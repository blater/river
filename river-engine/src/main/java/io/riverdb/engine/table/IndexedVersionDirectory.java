package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Disk-resident sparse row-version overrides with a bounded fixed-record cache. */
final class IndexedVersionDirectory {
  static final String FILE_NAME = "river.indexed.versions";
  static final int RECORD_BYTES = Long.BYTES * 4;
  private static final int PAGE_RECORDS = 2048;
  private static final int PAGE_BYTES = RECORD_BYTES * PAGE_RECORDS;
  private static final int FRAME_COUNT = 64;
  private static final long DELETED_FLAG = 1;
  private static final long VACUUM_DELETED_FLAG = 1;

  private final DurableFile file;
  private final VersionFrame[] frames = new VersionFrame[FRAME_COUNT];
  private final IoResult io = new IoResult();
  private long[] checkpointPages = new long[8];
  private int checkpointPageCount;
  private int denseCheckpointPageCount;
  private long accessClock;
  private long cachedRowId;
  private long cachedCommitSequence;
  private long cachedPreviousRowId;
  private long cachedFlags;
  private long cachedVacuumFlags;
  private boolean cached;
  private StatusCode lastStatus = StatusCode.OK;

  IndexedVersionDirectory(DurableFile durableFile) {
    file = durableFile;
  }

  StatusCode set(long rowId, long commitSequence, long previousRowId, boolean deleted) {
    return write(rowId, commitSequence, previousRowId, deleted ? DELETED_FLAG : 0, 0);
  }

  StatusCode setVacuumDeleted(long rowId, boolean deleted) {
    boolean present = read(rowId);
    if (!present && !lastStatus.isOk()) return lastStatus;
    long flags = present ? cachedFlags : 0;
    return write(
        rowId,
        present ? cachedCommitSequence : 0,
        present ? cachedPreviousRowId : 0,
        flags,
        deleted ? VACUUM_DELETED_FLAG : 0);
  }

  StatusCode clearVacuumDeleted(long rowId) {
    if (!read(rowId)) return lastStatus;
    return write(rowId, cachedCommitSequence, cachedPreviousRowId, cachedFlags, 0);
  }

  boolean read(long rowId) {
    if (rowId <= 0 || rowId > IndexedTableLimits.MAX_ROWS) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return false;
    }
    if (cached && cachedRowId == rowId) return true;
    long zeroBased = rowId - 1;
    long pageIndex = zeroBased / PAGE_RECORDS;
    int offset = (int) (zeroBased % PAGE_RECORDS) * RECORD_BYTES;
    VersionFrame frame = frame(pageIndex);
    if (frame == null) return false;
    cachedRowId = rowId;
    cachedCommitSequence = frame.bytes.getLong(offset);
    cachedPreviousRowId = frame.bytes.getLong(offset + Long.BYTES);
    cachedFlags = frame.bytes.getLong(offset + Long.BYTES * 2);
    cachedVacuumFlags = frame.bytes.getLong(offset + Long.BYTES * 3);
    cached = cachedCommitSequence > 0 || cachedVacuumFlags != 0;
    return cached;
  }

  StatusCode lookup(long rowId, IndexedVersionRecord result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    boolean present = read(rowId);
    if (!present) return lastStatus;
    result.set(cachedCommitSequence, cachedPreviousRowId, deleted());
    return StatusCode.OK;
  }

  StatusCode ensure(long rowId) {
    if (rowId <= 0 || rowId > IndexedTableLimits.MAX_ROWS) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return lastStatus;
    }
    long pageIndex = (rowId - 1) / PAGE_RECORDS;
    return frame(pageIndex) == null ? lastStatus : StatusCode.OK;
  }

  long commitSequence() {
    return cachedCommitSequence;
  }

  long previousRowId() {
    return cachedPreviousRowId;
  }

  boolean deleted() {
    return (cachedFlags & DELETED_FLAG) != 0;
  }

  boolean vacuumDeleted() {
    return (cachedVacuumFlags & VACUUM_DELETED_FLAG) != 0;
  }

  StatusCode clear() {
    StatusCode status = file.truncate(0);
    if (!status.isOk()) {
      lastStatus = status;
      return status;
    }
    for (VersionFrame frame : frames) {
      if (frame != null) {
        frame.filePage = -1;
        frame.dirty = false;
      }
    }
    cached = false;
    checkpointPageCount = 0;
    denseCheckpointPageCount = 0;
    lastStatus = StatusCode.OK;
    return StatusCode.OK;
  }

  StatusCode flush() {
    for (VersionFrame frame : frames) {
      if (frame == null || !frame.dirty) continue;
      frame.bytes.position(0);
      frame.bytes.limit(PAGE_BYTES);
      StatusCode status = file.write(
          frame.filePage * PAGE_BYTES, frame.bytes, io);
      if (!status.isOk()) {
        lastStatus = status;
        return status;
      }
      if (io.bytesTransferred() != PAGE_BYTES) {
        lastStatus = StatusCode.IO_FAILURE;
        return lastStatus;
      }
      frame.dirty = false;
    }
    StatusCode status = file.force(ForceMode.CONTENT_AND_METADATA);
    if (!status.isOk()) lastStatus = status;
    return status;
  }

  boolean hasDirtyPages() {
    for (VersionFrame frame : frames) {
      if (frame != null && frame.dirty) return true;
    }
    return false;
  }

  StatusCode lastStatus() {
    return lastStatus;
  }

  StatusCode close() {
    return file.close();
  }

  int checkpointPageCount() {
    long count = (long) denseCheckpointPageCount + checkpointPageCount;
    return (int) Math.min(Integer.MAX_VALUE, count);
  }

  long checkpointPageId(int index) {
    if (index < 0) return -1;
    if (index < denseCheckpointPageCount) return index;
    int sparse = index - denseCheckpointPageCount;
    return sparse < checkpointPageCount ? checkpointPages[sparse] : -1;
  }

  StatusCode beginVacuumPublication(long retainedRows) {
    if (retainedRows <= 0 || retainedRows > IndexedTableLimits.MAX_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long densePages = (retainedRows + PAGE_RECORDS - 1L) / PAGE_RECORDS;
    if (densePages > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    denseCheckpointPageCount = (int) densePages;
    checkpointPageCount = 0;
    for (VersionFrame frame : frames) {
      if (frame != null && frame.filePage >= densePages) {
        frame.filePage = -1;
        frame.dirty = false;
      }
    }
    cached = false;
    StatusCode status = file.truncate(densePages * PAGE_BYTES);
    if (!status.isOk()) lastStatus = status;
    return status;
  }

  private StatusCode write(
      long rowId,
      long commitSequence,
      long previousRowId,
      long flags,
      long vacuumFlags) {
    if (rowId <= 0 || rowId > IndexedTableLimits.MAX_ROWS || commitSequence < 0
        || previousRowId < 0 || previousRowId >= rowId) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return lastStatus;
    }
    long zeroBased = rowId - 1;
    long pageIndex = zeroBased / PAGE_RECORDS;
    int offset = (int) (zeroBased % PAGE_RECORDS) * RECORD_BYTES;
    VersionFrame frame = frame(pageIndex);
    if (frame == null) return lastStatus;
    try {
      registerCheckpointPage(pageIndex);
    } catch (OutOfMemoryError exhausted) {
      lastStatus = StatusCode.RESOURCE_EXHAUSTED;
      return lastStatus;
    }
    frame.bytes.putLong(offset, commitSequence);
    frame.bytes.putLong(offset + Long.BYTES, previousRowId);
    frame.bytes.putLong(offset + Long.BYTES * 2, flags);
    frame.bytes.putLong(offset + Long.BYTES * 3, vacuumFlags);
    frame.dirty = true;
    cachedRowId = rowId;
    cachedCommitSequence = commitSequence;
    cachedPreviousRowId = previousRowId;
    cachedFlags = flags;
    cachedVacuumFlags = vacuumFlags;
    cached = commitSequence > 0 || vacuumFlags != 0;
    lastStatus = StatusCode.OK;
    return StatusCode.OK;
  }

  private void registerCheckpointPage(long pageId) {
    if (pageId < denseCheckpointPageCount) return;
    int low = 0;
    int high = checkpointPageCount - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      long candidate = checkpointPages[middle];
      if (candidate < pageId) low = middle + 1;
      else if (candidate > pageId) high = middle - 1;
      else return;
    }
    if (checkpointPageCount == checkpointPages.length) {
      checkpointPages = Arrays.copyOf(checkpointPages, checkpointPages.length << 1);
    }
    if (low < checkpointPageCount) {
      System.arraycopy(
          checkpointPages, low, checkpointPages, low + 1, checkpointPageCount - low);
    }
    checkpointPages[low] = pageId;
    checkpointPageCount++;
  }

  private VersionFrame frame(long pageIndex) {
    for (VersionFrame frame : frames) {
      if (frame != null && frame.filePage == pageIndex) {
        frame.access = ++accessClock;
        return frame;
      }
    }
    int slot = findFrame();
    if (slot < 0) {
      lastStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    VersionFrame frame = frames[slot];
    if (frame == null) {
      try {
        frame = frames[slot] = new VersionFrame();
      } catch (OutOfMemoryError error) {
        lastStatus = StatusCode.RESOURCE_EXHAUSTED;
        return null;
      }
    }
    if (frame.dirty) {
      frame.bytes.position(0);
      frame.bytes.limit(PAGE_BYTES);
      StatusCode status = file.write(frame.filePage * PAGE_BYTES, frame.bytes, io);
      if (!status.isOk() || io.bytesTransferred() != PAGE_BYTES) {
        lastStatus = status.isOk() ? StatusCode.IO_FAILURE : status;
        return null;
      }
      frame.dirty = false;
    }
    frame.filePage = pageIndex;
    frame.access = ++accessClock;
    for (int index = 0; index < PAGE_BYTES; index++) {
      frame.bytes.put(index, (byte) 0);
    }
    // Positional I/O consumes the buffer, including on partial failed reads.
    frame.bytes.clear();
    StatusCode status = file.read(pageIndex * PAGE_BYTES, frame.bytes, io);
    if (!status.isOk()) {
      lastStatus = status;
      frame.filePage = -1;
      return null;
    }
    if (io.bytesTransferred() < PAGE_BYTES) {
      for (int index = io.bytesTransferred(); index < PAGE_BYTES; index++) {
        frame.bytes.put(index, (byte) 0);
      }
    }
    lastStatus = StatusCode.OK;
    return frame;
  }

  private int findFrame() {
    int oldest = -1;
    long oldestAccess = Long.MAX_VALUE;
    for (int index = 0; index < frames.length; index++) {
      VersionFrame frame = frames[index];
      if (frame == null || frame.filePage < 0) return index;
      if (frame.access < oldestAccess) {
        oldest = index;
        oldestAccess = frame.access;
      }
    }
    return oldest;
  }

  private static final class VersionFrame {
    private final ByteBuffer bytes = ByteBuffer.allocateDirect(PAGE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    private long filePage = -1;
    private long access;
    private boolean dirty;
  }
}
