package io.riverdb.platform.riverd.ntfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.FileIdentity;
import java.lang.foreign.MemorySegment;

/** Inspects Windows handles and validates directory/file security capabilities. */
final class WindowsDirectoryInspection {
  private WindowsDirectoryInspection() {
  }

  static Stat inspect(MemorySegment handle) {
    FileIdentity identity = WindowsFileBridge.identity(handle);
    if (identity == null) return null;
    int attributes = WindowsFileBridge.attributes(handle);
    if (attributes < 0) return null;
    int links = WindowsFileBridge.links(handle);
    if (links < 0) return null;
    return new Stat(identity, (attributes & WindowsFileBridge.FILE_ATTRIBUTE_DIRECTORY) != 0,
        (attributes & WindowsFileBridge.FILE_ATTRIBUTE_REPARSE_POINT) != 0,
        links, (attributes & WindowsFileBridge.FILE_ATTRIBUTE_DIRECTORY) == 0);
  }

  static StatusCode verifyPrivate(MemorySegment handle, Stat stat) {
    if (!stat.directory || stat.reparse) return StatusCode.CONFLICT;
    int security = WindowsFileBridge.verifyOwnerAndDacl(handle, true);
    return security == WindowsFileBridge.STATUS_SUCCESS ? StatusCode.OK
        : security == WindowsFileBridge.STATUS_ACCESS_DENIED ? StatusCode.ACCESS_DENIED
        : WindowsRiverDaemonFileSystem.status(security);
  }

  static StatusCode verifyAncestor(MemorySegment handle, Stat stat) {
    if (!stat.directory || stat.reparse) return StatusCode.CONFLICT;
    int security = WindowsFileBridge.verifyOwnerAndDacl(handle, false);
    return security == WindowsFileBridge.STATUS_SUCCESS ? StatusCode.OK
        : security == WindowsFileBridge.STATUS_ACCESS_DENIED
            || security == WindowsFileBridge.ERROR_ACCESS_DENIED ? StatusCode.ACCESS_DENIED
        : WindowsRiverDaemonFileSystem.status(security);
  }

  static StatusCode verifyFile(MemorySegment handle, Stat stat) {
    if (!stat.regular || stat.reparse) return StatusCode.CONFLICT;
    if (stat.links != 1) return StatusCode.ACCESS_DENIED;
    int security = WindowsFileBridge.verifyOwnerAndDacl(handle, true);
    return security == WindowsFileBridge.STATUS_SUCCESS ? StatusCode.OK
        : security == WindowsFileBridge.STATUS_ACCESS_DENIED ? StatusCode.ACCESS_DENIED
        : WindowsRiverDaemonFileSystem.status(security);
  }

  static final class Stat {
    final FileIdentity identity;
    final boolean directory;
    final boolean reparse;
    final int links;
    final boolean regular;

    Stat(FileIdentity identity, boolean directory, boolean reparse, int links, boolean regular) {
      this.identity = identity;
      this.directory = directory;
      this.reparse = reparse;
      this.links = links;
      this.regular = regular;
    }
  }
}
