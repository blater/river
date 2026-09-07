package io.riverdb.platform.riverd.linux;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverLockResult;
import java.nio.file.Path;

/** Linux ext4/XFS descriptor-relative riverd filesystem adapter. */
public final class LinuxRiverDaemonFileSystem implements RiverDaemonFileSystem {
  private static final int DIRECTORY_FLAGS = LinuxFileBridge.O_RDONLY
      | LinuxFileBridge.O_DIRECTORY | LinuxFileBridge.O_CLOEXEC | LinuxFileBridge.O_NOFOLLOW;

  @Override
  public StatusCode openAncestor(Path path, RiverDirectoryResult result) {
    return openPath(path, result, false);
  }

  @Override
  public StatusCode openDirectory(Path path, RiverDirectoryResult result) {
    return openPath(path, result, true);
  }

  private StatusCode openPath(Path path, RiverDirectoryResult result, boolean privateRequired) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!linux() || path == null || invalidPath(path)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!LinuxFileBridge.supportedArchitecture()) return StatusCode.FEATURE_NOT_SUPPORTED;
    int fd = LinuxFileBridge.open(path.toAbsolutePath().normalize(), DIRECTORY_FLAGS, 0);
    if (fd < 0) return status(LinuxFileBridge.errno());
    LinuxFileBridge.Stat stat = LinuxFileBridge.stat(fd);
    StatusCode check = privateRequired ? verifyPrivateDirectory(stat) : verifyDirectory(stat);
    if (!check.isOk()) {
      LinuxFileBridge.close(fd);
      return check;
    }
    result.set(new LinuxRiverDirectory(fd, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public StatusCode acquireExclusive(RiverFile file, RiverLockResult result) {
    if (file == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!(file instanceof LinuxRiverFile linuxFile)) return StatusCode.INVALID_EXTERNAL_INPUT;
    synchronized (linuxFile) {
      StatusCode reservation = linuxFile.reserveLock();
      if (!reservation.isOk()) return reservation;
      int lockFd = LinuxFileBridge.duplicate(linuxFile.fd());
      if (lockFd < 0) {
        int error = LinuxFileBridge.errno();
        linuxFile.cancelLock();
        return status(error);
      }
      if (LinuxFileBridge.lock(lockFd) != 0) {
        int error = LinuxFileBridge.errno();
        LinuxFileBridge.close(lockFd);
        linuxFile.cancelLock();
        return error == LinuxFileBridge.EWOULDBLOCK ? StatusCode.CONFLICT : status(error);
      }
      result.set(new LinuxRiverLock(linuxFile, lockFd));
      return StatusCode.OK;
    }
  }

  // Linux POSIX ACL_MASK is reflected in the group mode bits. Named user/group
  // entries cannot grant permissions outside that mask, including inherited ACLs.
  static StatusCode verifyDirectory(LinuxFileBridge.Stat stat) {
    if (stat == null) return status(LinuxFileBridge.errno());
    if (!stat.directory()) return StatusCode.CONFLICT;
    if (stat.uid != LinuxFileBridge.effectiveUid() || (stat.mode & 0022) != 0) {
      return StatusCode.ACCESS_DENIED;
    }
    return StatusCode.OK;
  }

  static StatusCode verifyPrivateDirectory(LinuxFileBridge.Stat stat) {
    StatusCode check = verifyDirectory(stat);
    if (!check.isOk()) return check;
    return (stat.mode & 0777) == 0700 ? StatusCode.OK : StatusCode.ACCESS_DENIED;
  }

  static StatusCode verifyFile(LinuxFileBridge.Stat stat) {
    if (stat == null) return status(LinuxFileBridge.errno());
    if (!stat.regularFile()) return StatusCode.CONFLICT;
    if (stat.links != 1) return StatusCode.ACCESS_DENIED;
    if (stat.uid != LinuxFileBridge.effectiveUid() || (stat.mode & 0077) != 0) {
      return StatusCode.ACCESS_DENIED;
    }
    return StatusCode.OK;
  }

  static StatusCode status(int error) {
    return switch (error) {
      case LinuxFileBridge.EEXIST, LinuxFileBridge.ENOENT, LinuxFileBridge.ENOTDIR,
          LinuxFileBridge.ENOTEMPTY -> StatusCode.CONFLICT;
      case LinuxFileBridge.EACCES, LinuxFileBridge.ELOOP -> StatusCode.ACCESS_DENIED;
      case LinuxFileBridge.ENOSPC -> StatusCode.RESOURCE_EXHAUSTED;
      case LinuxFileBridge.ENOTSUP, LinuxFileBridge.EINVAL, LinuxFileBridge.ENOSYS ->
          StatusCode.FEATURE_NOT_SUPPORTED;
      default -> StatusCode.IO_FAILURE;
    };
  }

  private static boolean linux() {
    return "Linux".equals(System.getProperty("os.name"));
  }

  private static boolean invalidPath(Path path) {
    String value = path.toString();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == 0 || character == '\r' || character == '\n'
          || Character.getType(character) == Character.CONTROL) return true;
    }
    return false;
  }
}
