package io.riverdb.platform.riverd.ntfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverLock;

final class WindowsRiverLock implements RiverLock {
  private final WindowsRiverFile file;
  private final java.lang.foreign.MemorySegment handle;
  private final java.lang.foreign.MemorySegment overlapped;
  private boolean closed;

  WindowsRiverLock(WindowsRiverFile file, java.lang.foreign.MemorySegment handle,
      java.lang.foreign.MemorySegment overlapped) {
    this.file = file;
    this.handle = handle;
    this.overlapped = overlapped;
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    int unlock = WindowsFileBridge.unlock(handle, overlapped);
    int unlockStatus = WindowsFileBridge.status();
    int close = WindowsFileBridge.close(handle);
    int closeStatus = WindowsFileBridge.status();
    StatusCode status = unlock == 0 && close == 0 ? StatusCode.OK
        : WindowsRiverDaemonFileSystem.status(unlock != 0 ? unlockStatus : closeStatus);
    file.releaseLockReservation();
    return status;
  }
}
