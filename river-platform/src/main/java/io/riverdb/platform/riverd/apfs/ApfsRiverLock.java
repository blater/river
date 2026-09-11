package io.riverdb.platform.riverd.apfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverLock;

final class ApfsRiverLock implements RiverLock {
  private final ApfsRiverFile file;
  private final int lockFd;
  private boolean closed;

  ApfsRiverLock(ApfsRiverFile owner, int descriptor) {
    file = owner;
    lockFd = descriptor;
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    int unlockStatus = DarwinFileBridge.unlock(lockFd);
    int unlockError = unlockStatus == 0 ? 0 : DarwinNativeBindings.errno();
    int closeStatus = DarwinFileBridge.close(lockFd);
    int closeError = closeStatus == 0 ? 0 : DarwinNativeBindings.errno();
    file.releaseLockReservation();
    if (unlockStatus != 0) return ApfsRiverDaemonFileSystem.status(unlockError);
    return closeStatus == 0 ? StatusCode.OK : ApfsRiverDaemonFileSystem.status(closeError);
  }
}
