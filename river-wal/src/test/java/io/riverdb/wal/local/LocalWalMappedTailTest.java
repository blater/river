package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class LocalWalMappedTailTest {
  @Test
  void loadsEmptyStateAndChoosesHighestSequence() {
    MemoryFile file = new MemoryFile(256);
    LocalWalMappedTail tail = new LocalWalMappedTail();
    assertEquals(StatusCode.CORRUPTION, tail.load(file, 256));
    assertEquals(StatusCode.OK, tail.persist(file, 128));
    assertEquals(StatusCode.OK, tail.persist(file, 192));

    LocalWalMappedTail reopened = new LocalWalMappedTail();
    assertEquals(StatusCode.OK, reopened.load(file, 256));
    assertEquals(192L, reopened.logicalEnd());
    assertEquals(ForceMode.CONTENT_AND_METADATA, file.lastForce);
  }

  @Test
  void rejectsTornStateAndKeepsOlderValidSlot() {
    MemoryFile file = new MemoryFile(256);
    LocalWalMappedTail tail = new LocalWalMappedTail();
    assertEquals(StatusCode.OK, tail.persist(file, 128));
    file.bytes[64 + 24] ^= 1;
    LocalWalMappedTail torn = new LocalWalMappedTail();
    assertEquals(StatusCode.CORRUPTION, torn.load(file, 256));

    file = new MemoryFile(256);
    tail = new LocalWalMappedTail();
    assertEquals(StatusCode.OK, tail.persist(file, 128));
    assertEquals(StatusCode.OK, tail.persist(file, 192));
    file.bytes[96 + 24] ^= 1;
    LocalWalMappedTail older = new LocalWalMappedTail();
    assertEquals(StatusCode.OK, older.load(file, 256));
    assertEquals(128L, older.logicalEnd());
  }

  @Test
  void validatesRangeAndDoesNotAdvanceAfterFailedForce() {
    MemoryFile file = new MemoryFile(256);
    LocalWalMappedTail tail = new LocalWalMappedTail();
    assertEquals(StatusCode.OK, tail.persist(file, 192));
    LocalWalMappedTail outOfRange = new LocalWalMappedTail();
    assertEquals(StatusCode.CORRUPTION, outOfRange.load(file, 160));

    file = new MemoryFile(256);
    tail = new LocalWalMappedTail();
    assertEquals(StatusCode.OK, tail.persist(file, 128));
    file.failForce = true;
    assertEquals(StatusCode.IO_FAILURE, tail.persist(file, 192));
    assertEquals(128L, tail.logicalEnd());
    file.failForce = false;
    assertEquals(StatusCode.OK, tail.persist(file, 192));
    assertEquals(192L, tail.logicalEnd());
  }

  private static final class MemoryFile implements DurableFile {
    private final byte[] bytes;
    private final long size;
    private boolean failForce;
    private ForceMode lastForce;

    MemoryFile(int size) {
      this.bytes = new byte[size];
      this.size = size;
    }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      if (position < 0 || position >= size) {
        result.setBytesTransferred(0);
        return StatusCode.IO_FAILURE;
      }
      int count = Math.min(target.remaining(), (int) (size - position));
      target.put(bytes, (int) position, count);
      result.setBytesTransferred(count);
      return StatusCode.OK;
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      if (position < 0 || position >= size) {
        result.setBytesTransferred(0);
        return StatusCode.IO_FAILURE;
      }
      int count = Math.min(source.remaining(), (int) (size - position));
      source.get(bytes, (int) position, count);
      result.setBytesTransferred(count);
      return StatusCode.OK;
    }

    @Override
    public StatusCode force(ForceMode mode) {
      lastForce = mode;
      return failForce ? StatusCode.IO_FAILURE : StatusCode.OK;
    }

    @Override
    public StatusCode truncate(long sizeBytes) {
      return sizeBytes == size ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }

    @Override
    public StatusCode size(FileSizeResult result) {
      result.setSizeBytes(size);
      return StatusCode.OK;
    }

    @Override
    public StatusCode close() {
      return StatusCode.OK;
    }
  }
}
