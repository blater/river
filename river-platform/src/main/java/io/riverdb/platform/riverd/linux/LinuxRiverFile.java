package io.riverdb.platform.riverd.linux;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverFile;
import java.nio.ByteBuffer;

final class LinuxRiverFile implements RiverFile {
  private final int fd;
  private final FileIdentity identity;
  private boolean closed;
  private boolean lockReserved;

  LinuxRiverFile(int fd, FileIdentity identity) {
    this.fd = fd;
    this.identity = identity;
  }

  int fd() { return fd; }
  @Override
  public FileIdentity identity() { return identity; }

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
    int count = LinuxFileBridge.read(fd, target, position);
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
    int count = LinuxFileBridge.write(fd, source, position);
    if (count < 0) return failure(result);
    source.position(before + count);
    result.setBytesTransferred(count);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode force(ForceMode mode) {
    if (mode == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed) return StatusCode.CLOSED;
    return LinuxFileBridge.force(fd) == 0 ? StatusCode.OK
        : LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
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
    return LinuxFileBridge.truncate(fd, sizeBytes) == 0 ? StatusCode.OK
        : LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
  }

  @Override
  public synchronized StatusCode size(FileSizeResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed) return StatusCode.CLOSED;
    LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.stat(fd);
    if (stat == null) return LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
    result.setSizeBytes(stat.size);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    return LinuxFileBridge.close(fd) == 0 ? StatusCode.OK
        : LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
  }

  private StatusCode failure(IoResult result) {
    result.setBytesTransferred(0);
    return LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
  }
}
