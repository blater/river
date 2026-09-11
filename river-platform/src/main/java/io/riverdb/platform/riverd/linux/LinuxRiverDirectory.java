package io.riverdb.platform.riverd.linux;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
final class LinuxRiverDirectory implements RiverDirectory {
  private static final Object CROSS_PARENT = new Object();
  private final int fd;
  private final FileIdentity identity;
  private boolean closed;

  LinuxRiverDirectory(int fd, FileIdentity identity) {
    this.fd = fd;
    this.identity = identity;
  }

  int fd() { return fd; }
  @Override
  public FileIdentity identity() { return identity; }

  @Override
  public synchronized StatusCode createDirectory(String name, RiverDirectoryResult result) {
    if (result == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (LinuxNamespaceBridge.mkdirAt(fd, name, 0700) != 0) return status();
    int child = LinuxFileBridge.openAt(fd, name, directoryFlags(), 0);
    if (child < 0) return status();
    LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.stat(child);
    if (stat == null) {
      StatusCode failure = status();
      LinuxFileBridge.close(child);
      return failure;
    }
    StatusCode check = LinuxRiverDaemonFileSystem.verifyPrivateDirectory(stat);
    if (!check.isOk()) {
      LinuxFileBridge.close(child);
      return removeCreated(name, stat.identity, check);
    }
    result.set(new LinuxRiverDirectory(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode openDirectory(String name, RiverDirectoryResult result) {
    if (result == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int child = LinuxFileBridge.openAt(fd, name, directoryFlags(), 0);
    if (child < 0) return status();
    LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.stat(child);
    StatusCode check = LinuxRiverDaemonFileSystem.verifyPrivateDirectory(stat);
    if (!check.isOk()) {
      LinuxFileBridge.close(child);
      return check;
    }
    result.set(new LinuxRiverDirectory(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode openFile(String name, RiverOpenMode mode, RiverFileResult result) {
    if (result == null || mode == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int flags = LinuxFileBridge.O_RDWR | LinuxFileBridge.O_CLOEXEC | LinuxFileBridge.O_NOFOLLOW;
    if (mode == RiverOpenMode.CREATE_NEW) flags |= LinuxFileBridge.O_CREAT | LinuxFileBridge.O_EXCL;
    int child = LinuxFileBridge.openAt(
        fd, name, flags, mode == RiverOpenMode.CREATE_NEW ? 0600 : 0);
    if (child < 0) return status();
    LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.stat(child);
    if (stat == null) {
      StatusCode failure = status();
      LinuxFileBridge.close(child);
      return failure;
    }
    StatusCode check = LinuxRiverDaemonFileSystem.verifyFile(stat);
    if (!check.isOk()) {
      LinuxFileBridge.close(child);
      return mode == RiverOpenMode.CREATE_NEW ? removeCreated(name, stat.identity, check) : check;
    }
    result.set(new LinuxRiverFile(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode list(DirectoryListResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!admission().isOk()) return admission();
    return LinuxDirectoryListing.list(fd, directoryFlags(), result);
  }

  @Override
  public synchronized StatusCode publishExclusive(
      RiverFile stage, String stageName, String targetName, DirectoryOperationResult result) {
    return publish(stage, stageName, targetName, result, true);
  }

  @Override
  public synchronized StatusCode publishReplacement(
      RiverFile stage, String stageName, String targetName, DirectoryOperationResult result) {
    return publish(stage, stageName, targetName, result, false);
  }

  private StatusCode publish(RiverFile stage, String stageName, String targetName,
      DirectoryOperationResult result, boolean exclusive) {
    if (result == null || !(stage instanceof LinuxRiverFile file)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validChild(stageName) || !validChild(targetName) || stageName.equals(targetName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!admission().isOk()) return admission();
    LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.statAt(fd, stageName);
    if (stat == null || !stat.regularFile() || stat.links != 1 || !stat.identity.equals(file.identity())) {
      return StatusCode.CONFLICT;
    }
    if (!exclusive) {
      StatusCode check = validateTarget(targetName);
      if (!check.isOk()) return check;
    }
    int status = LinuxNamespaceBridge.rename(fd, stageName, fd, targetName, exclusive);
    if (status == 0) {
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    }
    int error = LinuxNativeBindings.errno();
    result.set(null, error == LinuxFileBridge.EEXIST
        ? DirectoryDurability.NOT_APPLIED : DirectoryDurability.UNKNOWN);
    return LinuxRiverDaemonFileSystem.status(error);
  }

  @Override
  public StatusCode publishDirectoryExclusive(RiverDirectory sourceParent, RiverDirectory stage,
      String stageName, String targetName, DirectoryOperationResult result) {
    if (result == null || !(sourceParent instanceof LinuxRiverDirectory source)
        || !(stage instanceof LinuxRiverDirectory staged)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!validChild(stageName) || !validChild(targetName)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    synchronized (CROSS_PARENT) {
      synchronized (this) {
        synchronized (source) {
          if (!admission().isOk()) return admission();
          if (!source.admission().isOk()) return source.admission();
          LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.statAt(source.fd, stageName);
          if (stat == null || !stat.directory() || !stat.identity.equals(staged.identity)) {
            return StatusCode.CONFLICT;
          }
          int status = LinuxNamespaceBridge.rename(source.fd, stageName, fd, targetName, true);
          if (status != 0) {
            int error = LinuxNativeBindings.errno();
            result.set(null, error == LinuxFileBridge.EEXIST
                ? DirectoryDurability.NOT_APPLIED : DirectoryDurability.UNKNOWN);
            return LinuxRiverDaemonFileSystem.status(error);
          }
          int sourceForce = LinuxFileBridge.force(source.fd);
          int targetForce = sourceForce == 0 ? LinuxFileBridge.force(fd) : -1;
          if (sourceForce == 0 && targetForce == 0) {
            result.set(null, DirectoryDurability.DURABLE);
            return StatusCode.OK;
          }
          result.set(null, DirectoryDurability.UNKNOWN);
          return LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
        }
      }
    }
  }

  @Override
  public synchronized StatusCode removeOwned(String name, FileIdentity expected,
      DirectoryOperationResult result) {
    if (result == null || expected == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.statAt(fd, name);
    if (stat == null) return status();
    if (!stat.regularFile() && !stat.directory()) return StatusCode.CONFLICT;
    if (!stat.identity.equals(expected)) return StatusCode.CONFLICT;
    int remove = LinuxNamespaceBridge.unlinkAt(fd, name, stat.directory());
    if (remove == 0) {
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    }
    int error = LinuxNativeBindings.errno();
    result.set(null, DirectoryDurability.UNKNOWN);
    return LinuxRiverDaemonFileSystem.status(error);
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!admission().isOk()) return admission();
    int status = LinuxFileBridge.force(fd);
    if (status == 0) {
      result.set(null, DirectoryDurability.DURABLE);
      return StatusCode.OK;
    }
    result.set(null, DirectoryDurability.UNKNOWN);
    return LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    return LinuxFileBridge.close(fd) == 0 ? StatusCode.OK
        : LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno());
  }

  private StatusCode validateTarget(String name) {
    int target = LinuxFileBridge.openAt(fd, name, fileFlags(), 0);
    if (target < 0) {
      int error = LinuxNativeBindings.errno();
      return error == LinuxFileBridge.ENOENT ? StatusCode.OK
          : LinuxRiverDaemonFileSystem.status(error);
    }
    LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.stat(target);
    if (stat == null) {
      StatusCode failure = status();
      LinuxFileBridge.close(target);
      return failure;
    }
    StatusCode check = LinuxRiverDaemonFileSystem.verifyFile(stat);
    LinuxFileBridge.close(target);
    return check;
  }

  private StatusCode removeCreated(String name, FileIdentity identity, StatusCode primary) {
    DirectoryOperationResult removed = new DirectoryOperationResult();
    StatusCode status = removeOwned(name, identity, removed);
    if (!status.isOk()) return status;
    DirectoryOperationResult forced = new DirectoryOperationResult();
    StatusCode force = force(forced);
    return force.isOk() ? primary : force;
  }

  private StatusCode admission() { return closed ? StatusCode.CLOSED : StatusCode.OK; }
  private StatusCode status() { return LinuxRiverDaemonFileSystem.status(LinuxNativeBindings.errno()); }
  private boolean begin(String name) { return admission().isOk() && validChild(name); }

  private static int directoryFlags() {
    return LinuxFileBridge.O_RDONLY | LinuxFileBridge.O_DIRECTORY
        | LinuxFileBridge.O_CLOEXEC | LinuxFileBridge.O_NOFOLLOW;
  }

  private static int fileFlags() {
    return LinuxFileBridge.O_RDWR | LinuxFileBridge.O_CLOEXEC | LinuxFileBridge.O_NOFOLLOW;
  }

  private static boolean validChild(String name) {
    if (name == null || name.isBlank() || name.length() > 255
        || name.equals(".") || name.equals("..")) return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '/' || c == '\\' || c == 0 || c == '\r' || c == '\n'
          || Character.getType(c) == Character.CONTROL) return false;
    }
    return true;
  }
}
