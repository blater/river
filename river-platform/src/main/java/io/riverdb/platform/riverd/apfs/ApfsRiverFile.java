package io.riverdb.platform.riverd.apfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverFile;
import java.nio.ByteBuffer;

final class ApfsRiverFile implements RiverFile {
  private final int fd;
  private final FileIdentity identity;
  private boolean closed;
  private boolean lockReserved;

  ApfsRiverFile(int descriptor, FileIdentity stableIdentity) {
    fd = descriptor;
    identity = stableIdentity;
  }

  int fd() {
    return fd;
  }

  synchronized StatusCode reserveLock() {
    if (closed) return StatusCode.CLOSED;
    if (lockReserved) return StatusCode.CONFLICT;
    lockReserved = true;
    return StatusCode.OK;
  }

  synchronized void cancelLock() {
    lockReserved = false;
  }

  synchronized void releaseLockReservation() {
    lockReserved = false;
  }

  @Override
  public FileIdentity identity() {
    return identity;
  }

  @Override
  public synchronized StatusCode read(long position, ByteBuffer target, IoResult result) {
    if (target == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    if (position < 0 || target.isReadOnly()) return StatusCode.INVALID_EXTERNAL_INPUT;
    int before = target.position();
    int count = DarwinFileBridge.read(fd, target, position);
    if (count < 0) {
      result.setBytesTransferred(0);
      return ApfsRiverDaemonFileSystem.status(DarwinFileBridge.errno());
    }
    result.setBytesTransferred(count);
    if (count == 0 && target.position() == before && target.hasRemaining()) {
      return StatusCode.OK;
    }
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode write(long position, ByteBuffer source, IoResult result) {
    if (source == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    if (position < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int count = DarwinFileBridge.write(fd, source, position);
    if (count < 0) {
      result.setBytesTransferred(0);
      return ApfsRiverDaemonFileSystem.status(DarwinFileBridge.errno());
    }
    source.position(source.position() + count);
    result.setBytesTransferred(count);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode force(ForceMode mode) {
    if (mode == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    return DarwinFileBridge.forceFile(fd) == 0
        ? StatusCode.OK : ApfsRiverDaemonFileSystem.status(DarwinFileBridge.errno());
  }

  @Override
  public synchronized StatusCode truncate(long sizeBytes) {
    if (sizeBytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    return DarwinFileBridge.truncate(fd, sizeBytes) == 0
        ? StatusCode.OK : ApfsRiverDaemonFileSystem.status(DarwinFileBridge.errno());
  }

  @Override
  public synchronized StatusCode size(FileSizeResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    DarwinFileBridge.NativeStat stat = DarwinFileBridge.stat(fd);
    if (stat == null) return ApfsRiverDaemonFileSystem.status(DarwinFileBridge.errno());
    result.setSizeBytes(stat.size);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    return DarwinFileBridge.close(fd) == 0
        ? StatusCode.OK : ApfsRiverDaemonFileSystem.status(DarwinFileBridge.errno());
  }

  private StatusCode admission() {
    return closed ? StatusCode.CLOSED : StatusCode.OK;
  }
}
