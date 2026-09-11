package io.riverdb.platform.riverd.ntfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverFile;
import java.nio.ByteBuffer;

final class WindowsRiverFile implements RiverFile {
  private final java.lang.foreign.MemorySegment handle;
  private final FileIdentity identity;
  private boolean closed;
  private boolean lockReserved;

  WindowsRiverFile(java.lang.foreign.MemorySegment handle, FileIdentity identity) {
    this.handle = handle;
    this.identity = identity;
  }

  java.lang.foreign.MemorySegment handle() { return handle; }
  @Override public FileIdentity identity() { return identity; }

  synchronized StatusCode reserveLock() {
    if (closed) return StatusCode.CLOSED;
    if (lockReserved) return StatusCode.CONFLICT;
    lockReserved = true;
    return StatusCode.OK;
  }

  synchronized void cancelLock() { lockReserved = false; }
  synchronized void releaseLockReservation() { lockReserved = false; }

  @Override
  public synchronized StatusCode read(long position, ByteBuffer target, IoResult result) {
    if (target == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (closed) return StatusCode.CLOSED;
    if (position < 0 || target.isReadOnly()) return StatusCode.INVALID_EXTERNAL_INPUT;
    int count = WindowsFileBridge.read(handle, target, position);
    if (count < 0) return failure(result);
    result.setBytesTransferred(count);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode write(long position, ByteBuffer source, IoResult result) {
    if (source == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (closed) return StatusCode.CLOSED;
    if (position < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int before = source.position();
    int count = WindowsFileBridge.write(handle, source, position);
    if (count < 0) return failure(result);
    source.position(before + count);
    result.setBytesTransferred(count);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode force(ForceMode mode) {
    if (mode == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed) return StatusCode.CLOSED;
    return WindowsFileBridge.force(handle) == 0 ? StatusCode.OK
        : WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
  }

  @Override
  public synchronized StatusCode force(long startOffset, long endOffset, ForceMode mode) {
    if (startOffset < 0 || endOffset <= startOffset) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return force(mode);
  }

  @Override
  public synchronized StatusCode truncate(long sizeBytes) {
    if (sizeBytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed) return StatusCode.CLOSED;
    return WindowsFileBridge.truncate(handle, sizeBytes) == 0 ? StatusCode.OK
        : WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
  }

  @Override
  public synchronized StatusCode size(FileSizeResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed) return StatusCode.CLOSED;
    long size = WindowsFileBridge.size(handle);
    if (size < 0) return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
    result.setSizeBytes(size);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    return WindowsFileBridge.close(handle) == 0 ? StatusCode.OK
        : WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
  }

  private StatusCode failure(IoResult result) {
    result.setBytesTransferred(0);
    return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
  }
}
