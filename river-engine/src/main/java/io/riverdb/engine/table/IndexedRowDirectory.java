package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/** Bounded-cache row-to-heap directory; the directory itself is disk-resident. */
final class IndexedRowDirectory {
  static final String FILE_NAME = "river.indexed.rows";
  private static final int HEADER_MAGIC = 0x524F5744;
  private static final int HEADER_VERSION = 1;
  private static final int HEADER_BYTES = 64;
  private static final int HEADER_MAGIC_OFFSET = 0;
  private static final int HEADER_VERSION_OFFSET = 4;
  private static final int HEADER_ROW_COUNT_OFFSET = 8;
  private static final int HEADER_LAST_HEAP_PAGE_OFFSET = 16;
  private static final int HEADER_COMMIT_SEQUENCE_OFFSET = 24;
  private static final int HEADER_CHECKSUM_OFFSET = 32;
  private static final int RECORD_BYTES = Integer.BYTES * 2;
  private static final int PAGE_SHIFT = 16;
  private static final int PAGE_BYTES = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_BYTES - 1;
  private static final int FRAME_COUNT = 64;

  private final DurableFile file;
  private final DirectoryFrame[] frames = new DirectoryFrame[FRAME_COUNT];
  private final IoResult io = new IoResult();
  private final ByteBuffer header = ByteBuffer.allocateDirect(HEADER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final CRC32C headerChecksum = new CRC32C();
  private final ByteBuffer record = ByteBuffer.allocateDirect(RECORD_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private long accessClock;
  private long publishedRowCount;
  private int publishedLastHeapPageId;
  private long publishedCommitSequence;
  private boolean headerValid;
  private boolean headerDirty;
  private StatusCode lastStatus = StatusCode.OK;

  IndexedRowDirectory(DurableFile durableFile) {
    file = durableFile;
    readHeader();
  }

  boolean matches(long rowCount, long commitSequence) {
    return headerValid
        && publishedRowCount == rowCount
        && publishedCommitSequence == commitSequence;
  }

  long publishedRowCount() {
    return publishedRowCount;
  }

  int publishedLastHeapPageId() {
    return publishedLastHeapPageId;
  }

  void setPublishedState(long rowCount, int lastHeapPageId, long commitSequence) {
    if (rowCount < 0 || rowCount > IndexedTableLimits.MAX_ROWS
        || lastHeapPageId <= 0 || lastHeapPageId > IndexedTableLimits.MAX_PAGES
        || commitSequence <= 0) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return;
    }
    publishedRowCount = rowCount;
    publishedLastHeapPageId = lastHeapPageId;
    publishedCommitSequence = commitSequence;
    headerDirty = true;
  }

  int pageId(long rowId) {
    return read(rowId) ? record.getInt(0) : 0;
  }

  int slot(long rowId) {
    return read(rowId) ? record.getInt(Integer.BYTES) : 0;
  }

  StatusCode locate(long rowId, IndexedRowLocation result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!read(rowId)) return lastStatus;
    int pageId = record.getInt(0);
    int slot = record.getInt(Integer.BYTES);
    if (pageId <= 0 || pageId > IndexedTableLimits.MAX_PAGES || slot <= 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(pageId, slot);
    return StatusCode.OK;
  }

  StatusCode set(long rowId, int pageId, int slot) {
    if (rowId <= 0 || pageId <= 0 || pageId > IndexedTableLimits.MAX_PAGES
        || slot <= 0) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return lastStatus;
    }
    DirectoryFrame frame = frame(rowId);
    if (frame == null) return lastStatus;
    int offset = (int) ((rowId - 1) * RECORD_BYTES & PAGE_MASK);
    frame.bytes.putInt(offset, pageId);
    frame.bytes.putInt(offset + Integer.BYTES, slot);
    frame.dirty = true;
    lastStatus = StatusCode.OK;
    return StatusCode.OK;
  }

  StatusCode ensure(long rowId) {
    if (rowId <= 0 || rowId > IndexedTableLimits.MAX_ROWS) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return lastStatus;
    }
    return frame(rowId) == null ? lastStatus : StatusCode.OK;
  }

  StatusCode flush() {
    for (DirectoryFrame frame : frames) {
      if (frame == null || !frame.dirty) continue;
      frame.bytes.position(0);
      frame.bytes.limit(PAGE_BYTES);
      StatusCode status = file.write(frame.fileOffset, frame.bytes, io);
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
    if (headerDirty) {
      encodeHeader();
      header.position(0);
      header.limit(HEADER_BYTES);
      StatusCode status = file.write(0, header, io);
      if (!status.isOk()) {
        lastStatus = status;
        return status;
      }
      if (io.bytesTransferred() != HEADER_BYTES) {
        lastStatus = StatusCode.IO_FAILURE;
        return lastStatus;
      }
      headerDirty = false;
      headerValid = true;
    }
    StatusCode status = file.force(ForceMode.CONTENT_AND_METADATA);
    if (!status.isOk()) lastStatus = status;
    return status;
  }

  boolean hasDirtyPages() {
    if (headerDirty) return true;
    for (DirectoryFrame frame : frames) {
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

  private boolean read(long rowId) {
    if (rowId <= 0 || rowId > IndexedTableLimits.MAX_ROWS) {
      lastStatus = StatusCode.INVALID_EXTERNAL_INPUT;
      return false;
    }
    DirectoryFrame frame = frame(rowId);
    if (frame == null) return false;
    int offset = (int) ((rowId - 1) * RECORD_BYTES & PAGE_MASK);
    record.clear();
    record.putInt(frame.bytes.getInt(offset));
    record.putInt(frame.bytes.getInt(offset + Integer.BYTES));
    record.flip();
    return true;
  }

  private DirectoryFrame frame(long rowId) {
    long byteOffset = (rowId - 1) * RECORD_BYTES;
    long pageOffset = HEADER_BYTES + (byteOffset & -((long) PAGE_BYTES));
    for (DirectoryFrame frame : frames) {
      if (frame != null && frame.fileOffset == pageOffset) {
        frame.access = ++accessClock;
        return frame;
      }
    }
    int slot = findFrame();
    if (slot < 0) {
      lastStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    DirectoryFrame frame = frames[slot];
    if (frame == null) {
      try {
        frame = frames[slot] = new DirectoryFrame();
      } catch (OutOfMemoryError error) {
        lastStatus = StatusCode.RESOURCE_EXHAUSTED;
        return null;
      }
    }
    if (frame.dirty) {
      frame.bytes.position(0);
      frame.bytes.limit(PAGE_BYTES);
      StatusCode status = file.write(frame.fileOffset, frame.bytes, io);
      if (!status.isOk() || io.bytesTransferred() != PAGE_BYTES) {
        lastStatus = status.isOk() ? StatusCode.IO_FAILURE : status;
        return null;
      }
      frame.dirty = false;
    }
    frame.fileOffset = pageOffset;
    frame.access = ++accessClock;
    frame.dirty = false;
    for (int index = 0; index < PAGE_BYTES; index++) {
      frame.bytes.put(index, (byte) 0);
    }
    // Positional I/O consumes the buffer, including on partial failed reads.
    frame.bytes.clear();
    StatusCode status = file.read(pageOffset, frame.bytes, io);
    if (!status.isOk()) {
      lastStatus = status;
      frame.fileOffset = -1;
      return null;
    }
    if (io.bytesTransferred() < PAGE_BYTES) {
      // A short read at EOF is a valid zero-filled tail of a newly growing directory.
      for (int index = io.bytesTransferred(); index < PAGE_BYTES; index++) {
        frame.bytes.put(index, (byte) 0);
      }
    }
    lastStatus = StatusCode.OK;
    return frame;
  }

  private void readHeader() {
    header.clear();
    StatusCode status = file.read(0, header, io);
    if (!status.isOk()) {
      lastStatus = status;
      return;
    }
    if (io.bytesTransferred() == 0) return;
    if (io.bytesTransferred() != HEADER_BYTES) {
      lastStatus = StatusCode.CORRUPTION;
      return;
    }
    if (header.getInt(HEADER_MAGIC_OFFSET) != HEADER_MAGIC
        || header.getInt(HEADER_VERSION_OFFSET) != HEADER_VERSION) {
      lastStatus = StatusCode.CORRUPTION;
      return;
    }
    int expected = header.getInt(HEADER_CHECKSUM_OFFSET);
    header.putInt(HEADER_CHECKSUM_OFFSET, 0);
    headerChecksum.reset();
    for (int index = 0; index < HEADER_CHECKSUM_OFFSET; index++) {
      headerChecksum.update(header.get(index));
    }
    if ((int) headerChecksum.getValue() != expected) {
      lastStatus = StatusCode.CORRUPTION;
      return;
    }
    publishedRowCount = header.getLong(HEADER_ROW_COUNT_OFFSET);
    publishedLastHeapPageId = header.getInt(HEADER_LAST_HEAP_PAGE_OFFSET);
    publishedCommitSequence = header.getLong(HEADER_COMMIT_SEQUENCE_OFFSET);
    headerValid = publishedRowCount >= 0
        && publishedRowCount <= IndexedTableLimits.MAX_ROWS
        && publishedLastHeapPageId > 0
        && publishedLastHeapPageId <= IndexedTableLimits.MAX_PAGES
        && publishedCommitSequence > 0;
    if (!headerValid) lastStatus = StatusCode.CORRUPTION;
  }

  private void encodeHeader() {
    header.clear();
    header.putInt(HEADER_MAGIC_OFFSET, HEADER_MAGIC);
    header.putInt(HEADER_VERSION_OFFSET, HEADER_VERSION);
    header.putLong(HEADER_ROW_COUNT_OFFSET, publishedRowCount);
    header.putInt(HEADER_LAST_HEAP_PAGE_OFFSET, publishedLastHeapPageId);
    header.putLong(HEADER_COMMIT_SEQUENCE_OFFSET, publishedCommitSequence);
    header.putInt(HEADER_CHECKSUM_OFFSET, 0);
    headerChecksum.reset();
    for (int index = 0; index < HEADER_CHECKSUM_OFFSET; index++) {
      headerChecksum.update(header.get(index));
    }
    header.putInt(HEADER_CHECKSUM_OFFSET, (int) headerChecksum.getValue());
  }

  private int findFrame() {
    int oldest = -1;
    long oldestAccess = Long.MAX_VALUE;
    for (int index = 0; index < frames.length; index++) {
      DirectoryFrame frame = frames[index];
      if (frame == null || frame.fileOffset < 0) return index;
      if (frame.access < oldestAccess) {
        oldest = index;
        oldestAccess = frame.access;
      }
    }
    return oldest;
  }

  private static final class DirectoryFrame {
    private final ByteBuffer bytes = ByteBuffer.allocateDirect(PAGE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    private long fileOffset = -1;
    private long access;
    private boolean dirty;
  }
}
