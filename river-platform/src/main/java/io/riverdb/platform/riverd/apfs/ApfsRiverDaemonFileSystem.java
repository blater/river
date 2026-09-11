package io.riverdb.platform.riverd.apfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverLock;
import io.riverdb.platform.riverd.RiverLockResult;
import java.nio.file.Path;

/** macOS/APFS descriptor-relative riverd filesystem adapter. */
public final class ApfsRiverDaemonFileSystem implements RiverDaemonFileSystem {
  private static final int DIRECTORY_FLAGS = DarwinFileBridge.O_RDONLY
      | DarwinFileBridge.O_DIRECTORY
      | DarwinFileBridge.O_CLOEXEC
      | DarwinFileBridge.O_NOFOLLOW_ANY;

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
    if (!macOs() || path == null || invalidPath(path)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int fd = DarwinFileBridge.open(path.toAbsolutePath().normalize(), DIRECTORY_FLAGS, 0);
    if (fd < 0) return status(DarwinNativeBindings.errno());
    DarwinNamespaceBridge.NativeStat stat = DarwinNamespaceBridge.stat(fd);
    StatusCode check = privateRequired
        ? verifyPrivateDirectory(fd, stat) : verifyDirectory(fd, stat);
    if (!check.isOk()) {
      DarwinFileBridge.close(fd);
      return check;
    }
    result.set(new ApfsRiverDirectory(fd, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public StatusCode acquireExclusive(RiverFile file, RiverLockResult result) {
    if (result == null || file == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!(file instanceof ApfsRiverFile apfsFile)) return StatusCode.INVALID_EXTERNAL_INPUT;
    synchronized (apfsFile) {
      StatusCode reservation = apfsFile.reserveLock();
      if (!reservation.isOk()) return reservation;
      int lockFd = DarwinFileBridge.duplicate(apfsFile.fd());
      if (lockFd < 0) {
        int error = DarwinNativeBindings.errno();
        apfsFile.cancelLock();
        return status(error);
      }
      int lockStatus = DarwinFileBridge.lock(lockFd);
      if (lockStatus != 0) {
        int error = DarwinNativeBindings.errno();
        DarwinFileBridge.close(lockFd);
        apfsFile.cancelLock();
        return error == DarwinFileBridge.EWOULDBLOCK
            ? StatusCode.CONFLICT : status(error);
      }
      result.set(new ApfsRiverLock(apfsFile, lockFd));
      return StatusCode.OK;
    }
  }

  static StatusCode verifyDirectory(int fd, DarwinNamespaceBridge.NativeStat stat) {
    if (stat == null) return status(DarwinNativeBindings.errno());
    if (!stat.directory()) return StatusCode.CONFLICT;
    if (stat.uid != DarwinNamespaceBridge.effectiveUid() || (stat.mode & 0022) != 0) {
      return StatusCode.ACCESS_DENIED;
    }
    int acl = DarwinNamespaceBridge.aclAllows(fd);
    return acl == 0 ? StatusCode.OK : acl > 0 ? StatusCode.ACCESS_DENIED
        : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  static StatusCode verifyPrivateDirectory(int fd, DarwinNamespaceBridge.NativeStat stat) {
    StatusCode status = verifyDirectory(fd, stat);
    if (!status.isOk()) return status;
    return (stat.mode & 0777) == 0700 ? StatusCode.OK : StatusCode.ACCESS_DENIED;
  }

  static StatusCode verifyFile(int fd, DarwinNamespaceBridge.NativeStat stat) {
    if (stat == null) return status(DarwinNativeBindings.errno());
    if (!stat.regularFile()) return StatusCode.CONFLICT;
    if (stat.links != 1) return StatusCode.ACCESS_DENIED;
    if (stat.uid != DarwinNamespaceBridge.effectiveUid() || (stat.mode & 0077) != 0) {
      return StatusCode.ACCESS_DENIED;
    }
    int acl = DarwinNamespaceBridge.aclAllows(fd);
    return acl == 0 ? StatusCode.OK : acl > 0 ? StatusCode.ACCESS_DENIED
        : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  static StatusCode status(int error) {
    return switch (error) {
      case DarwinNamespaceBridge.EEXIST, DarwinNamespaceBridge.ENOENT, DarwinNamespaceBridge.ENOTDIR,
          DarwinNamespaceBridge.ENOTEMPTY ->
          StatusCode.CONFLICT;
      case DarwinNamespaceBridge.EACCES, DarwinNamespaceBridge.ELOOP -> StatusCode.ACCESS_DENIED;
      case DarwinNamespaceBridge.ENOSPC -> StatusCode.RESOURCE_EXHAUSTED;
      default -> StatusCode.IO_FAILURE;
    };
  }

  private static boolean macOs() {
    return "Mac OS X".equals(System.getProperty("os.name"));
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
