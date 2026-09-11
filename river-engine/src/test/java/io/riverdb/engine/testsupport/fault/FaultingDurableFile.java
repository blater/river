package io.riverdb.engine.testsupport.fault;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.Arrays;

final class FaultingDurableFile implements DurableFile {
  private final FaultingDurableDirectory directory;
  private final FaultingDurableFaultBoundary faults;
  private final FaultingDurableEntry entry;
  private final long openedGeneration;
  private boolean closed;

  FaultingDurableFile(
      FaultingDurableDirectory directory,
      FaultingDurableFaultBoundary faults,
      FaultingDurableEntry entry,
      long openedGeneration) {
    this.directory = directory;
    this.faults = faults;
    this.entry = entry;
    this.openedGeneration = openedGeneration;
  }

  @Override
  public StatusCode read(long position, ByteBuffer target, IoResult result) {
    synchronized (directory) {
      result.reset();
      StatusCode status = checkState();
      if (!status.isOk()) {
        return status;
      }
      if (position < 0 || position > entry.volatileSize) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = faults.before(directory, DirectoryOperation.FILE_READ, position, target.remaining());
      if (!status.isOk()) {
        return status;
      }
      int transferred = Math.min(target.remaining(), entry.volatileSize - (int) position);
      FaultAction action = faults.action();
      if (action == FaultAction.SHORT_READ) {
        transferred = limitedTransfer(transferred, faults.argument());
      }
      int xor = action == FaultAction.CORRUPT_READ
              || action == FaultAction.DETECTED_CORRUPTION
          ? (int) (faults.argument() == 0 ? 1 : faults.argument())
          : 0;
      for (int index = 0; index < transferred; index++) {
        target.put((byte) (entry.volatileBytes[(int) position + index] ^ xor));
      }
      result.setBytesTransferred(transferred);
      status = action == FaultAction.DETECTED_CORRUPTION
          ? StatusCode.CORRUPTION
          : StatusCode.OK;
      if (status.isOk()) {
        status = faults.after(directory, DirectoryOperation.FILE_READ, position, transferred);
      }
      return status;
    }
  }

  @Override
  public StatusCode write(long position, ByteBuffer source, IoResult result) {
    synchronized (directory) {
      result.reset();
      StatusCode status = checkState();
      if (!status.isOk()) {
        return status;
      }
      if (position < 0 || position > entry.volatileBytes.length) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int requested = source.remaining();
      status = faults.before(directory, DirectoryOperation.FILE_WRITE, position, requested);
      if (!status.isOk()) {
        return status;
      }
      FaultAction action = faults.action();
      int available = entry.volatileBytes.length - (int) position;
      int transferred = Math.min(requested, available);
      status = requested > available ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
      if (action == FaultAction.SHORT_WRITE) {
        transferred = limitedTransfer(transferred, faults.argument());
      } else if (action == FaultAction.PARTIAL_WRITE || action == FaultAction.TORN_WRITE) {
        transferred = limitedTransfer(transferred, faults.argument());
        status = StatusCode.IO_FAILURE;
      } else if (action == FaultAction.DISK_FULL) {
        transferred = limitedTransfer(transferred, faults.argument());
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      source.get(entry.volatileBytes, (int) position, transferred);
      entry.volatileSize = Math.max(entry.volatileSize, (int) position + transferred);
      result.setBytesTransferred(transferred);
      if (action == FaultAction.TORN_WRITE) {
        System.arraycopy(
            entry.volatileBytes,
            (int) position,
            entry.durableBytes,
            (int) position,
            transferred);
        entry.durableSize = Math.max(entry.durableSize, (int) position + transferred);
      }
      if (status.isOk()) {
        status = faults.after(directory, DirectoryOperation.FILE_WRITE, position, transferred);
      }
      return status;
    }
  }

  @Override
  public StatusCode force(ForceMode mode) {
    synchronized (directory) {
      StatusCode status = checkState();
      if (!status.isOk()) {
        return status;
      }
      if (mode == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return forceInternal(0, entry.volatileSize, mode);
    }
  }

  /** Range-force adapter used by WAL tests; preserves the same fault hooks as full force. */
  @Override
  public StatusCode force(long startInclusive, long endExclusive, ForceMode mode) {
    synchronized (directory) {
      StatusCode status = checkState();
      if (!status.isOk()) {
        return status;
      }
      if (mode == null || startInclusive < 0 || endExclusive <= startInclusive) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return forceInternal(startInclusive, endExclusive, mode);
    }
  }

  private StatusCode forceInternal(
      long startInclusive, long endExclusive, ForceMode mode) {
    long effectiveStart = Math.min(startInclusive, entry.volatileSize);
    long effectiveEnd = Math.min(endExclusive, entry.volatileSize);
    int span = (int) (effectiveEnd - effectiveStart);
    StatusCode status = faults.before(directory, DirectoryOperation.FILE_FORCE, effectiveStart, span);
    if (!status.isOk()) {
      return status;
    }
    FaultAction action = faults.action();
    if (action == FaultAction.FORCE_FAILURE || action == FaultAction.DISK_FULL) {
      status = action == FaultAction.DISK_FULL
          ? StatusCode.RESOURCE_EXHAUSTED
          : StatusCode.IO_FAILURE;
      return status;
    }
    entry.publishContent(startInclusive, endExclusive, mode);
    return faults.after(directory, DirectoryOperation.FILE_FORCE, effectiveStart, span);
  }

  @Override
  public StatusCode truncate(long sizeBytes) {
    synchronized (directory) {
      StatusCode status = checkState();
      if (!status.isOk()) {
        return status;
      }
      if (sizeBytes < 0 || sizeBytes > entry.volatileBytes.length) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = faults.before(directory, DirectoryOperation.TRUNCATE, sizeBytes, 0);
      if (!status.isOk()) {
        return status;
      }
      if (sizeBytes < entry.volatileSize) {
        Arrays.fill(entry.volatileBytes, (int) sizeBytes, entry.volatileSize, (byte) 0);
      } else if (sizeBytes > entry.volatileSize) {
        Arrays.fill(entry.volatileBytes, entry.volatileSize, (int) sizeBytes, (byte) 0);
      }
      entry.volatileSize = (int) sizeBytes;
      return faults.after(directory, DirectoryOperation.TRUNCATE, sizeBytes, 0);
    }
  }

  @Override
  public StatusCode size(FileSizeResult result) {
    synchronized (directory) {
      StatusCode status = checkState();
      if (!status.isOk()) {
        return status;
      }
      result.setSizeBytes(entry.volatileSize);
      return StatusCode.OK;
    }
  }

  @Override
  public StatusCode close() {
    synchronized (directory) {
      if (closed) {
        return StatusCode.CLOSED;
      }
      closed = true;
      if (!directory.closeHandle(openedGeneration)) {
        return StatusCode.CANCELLED;
      }
      return StatusCode.OK;
    }
  }

  private StatusCode checkState() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    return directory.isLive(openedGeneration)
        ? StatusCode.OK
        : StatusCode.CANCELLED;
  }

  private static int limitedTransfer(int available, long requestedLimit) {
    return (int) Math.max(0, Math.min(available, requestedLimit));
  }
}
