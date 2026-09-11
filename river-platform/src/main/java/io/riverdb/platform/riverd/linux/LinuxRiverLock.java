package io.riverdb.platform.riverd.linux;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverLock;

final class LinuxRiverLock implements RiverLock {
  private final LinuxRiverFile file;
  private final int lockFd;
  private boolean closed;

  LinuxRiverLock(LinuxRiverFile file, int lockFd) {
    this.file = file;
    this.lockFd = lockFd;
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    int unlock = LinuxFileBridge.unlock(lockFd);
    int unlockError = unlock == 0 ? 0 : LinuxNativeBindings.errno();
    int close = LinuxFileBridge.close(lockFd);
    int closeError = close == 0 ? 0 : LinuxNativeBindings.errno();
    file.releaseLockReservation();
    if (unlock != 0) return LinuxRiverDaemonFileSystem.status(unlockError);
    return close == 0 ? StatusCode.OK : LinuxRiverDaemonFileSystem.status(closeError);
  }
}
