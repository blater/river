package io.riverdb.platform.riverd;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import io.riverdb.platform.riverd.linux.LinuxRiverDaemonFileSystem;
import io.riverdb.platform.riverd.ntfs.WindowsRiverDaemonFileSystem;

/** Selects the concrete platform adapter for the current supported operating system. */
public final class RiverDaemonFileSystems {
  private RiverDaemonFileSystems() {
  }

  public static StatusCode current(RiverDaemonFileSystemResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    String osName = System.getProperty("os.name", "");
    RiverDaemonFileSystem selected;
    if ("Mac OS X".equals(osName)) {
      selected = new ApfsRiverDaemonFileSystem();
    } else if ("Linux".equals(osName)) {
      selected = new LinuxRiverDaemonFileSystem();
    } else if (osName.startsWith("Windows")) {
      selected = new WindowsRiverDaemonFileSystem();
    } else {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    result.set(selected);
    return StatusCode.OK;
  }
}
