package io.riverdb.platform.riverd.ntfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryNames;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.lang.foreign.MemorySegment;

final class WindowsRiverDirectory implements RiverDirectory {
  private static final Object CROSS_PARENT = new Object();
  private final MemorySegment handle;
  private final FileIdentity identity;
  private boolean closed;

  WindowsRiverDirectory(MemorySegment handle, FileIdentity identity) {
    this.handle = handle;
    this.identity = identity;
  }

  MemorySegment handle() { return handle; }
  @Override public FileIdentity identity() { return identity; }

  @Override
  public synchronized StatusCode createDirectory(String name, RiverDirectoryResult result) {
    if (result == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    MemorySegment child = WindowsFileBridge.openAt(handle, name, true, true);
    if (child.equals(MemorySegment.NULL)) return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
    WindowsDirectoryInspection.Stat stat = WindowsDirectoryInspection.inspect(child);
    StatusCode check = stat == null ? WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status())
        : WindowsDirectoryInspection.verifyPrivate(child, stat);
    if (!check.isOk()) {
      WindowsFileBridge.remove(child);
      WindowsFileBridge.close(child);
      force(new DirectoryOperationResult());
      return check;
    }
    result.set(new WindowsRiverDirectory(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode openDirectory(String name, RiverDirectoryResult result) {
    if (result == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    MemorySegment child = WindowsFileBridge.openAt(handle, name, true, false);
    if (child.equals(MemorySegment.NULL)) return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
    WindowsDirectoryInspection.Stat stat = WindowsDirectoryInspection.inspect(child);
    StatusCode check = stat == null ? WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status())
        : WindowsDirectoryInspection.verifyPrivate(child, stat);
    if (!check.isOk()) {
      WindowsFileBridge.close(child);
      return check;
    }
    result.set(new WindowsRiverDirectory(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode openFile(String name, RiverOpenMode mode, RiverFileResult result) {
    if (result == null || mode == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    MemorySegment child = WindowsFileBridge.openAt(handle, name, false,
        mode == RiverOpenMode.CREATE_NEW);
    if (child.equals(MemorySegment.NULL)) return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
    WindowsDirectoryInspection.Stat stat = WindowsDirectoryInspection.inspect(child);
    StatusCode check = stat == null ? WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status())
        : WindowsDirectoryInspection.verifyFile(child, stat);
    if (!check.isOk()) {
      if (mode == RiverOpenMode.CREATE_NEW) {
        // CREATE_NEW owns this exact handle; delete through it before releasing the capability.
        WindowsFileBridge.remove(child);
        WindowsFileBridge.close(child);
        force(new DirectoryOperationResult());
      } else {
        WindowsFileBridge.close(child);
      }
      return check;
    }
    result.set(new WindowsRiverFile(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode list(DirectoryListResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    return WindowsDirectoryListing.list(handle, result);
  }

  @Override
  public synchronized StatusCode publishExclusive(
      RiverFile stage, String stageName, String targetName, DirectoryOperationResult result) {
    return publish(stage, stageName, targetName, result, false);
  }

  @Override
  public synchronized StatusCode publishReplacement(
      RiverFile stage, String stageName, String targetName, DirectoryOperationResult result) {
    return publish(stage, stageName, targetName, result, true);
  }

  private StatusCode publish(RiverFile stage, String stageName, String targetName,
      DirectoryOperationResult result, boolean replace) {
    if (result == null || !(stage instanceof WindowsRiverFile file)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!begin(stageName) || !RiverDirectoryNames.validWindows(targetName)
        || stageName.equals(targetName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    WindowsDirectoryInspection.Stat stat = WindowsDirectoryInspection.inspect(file.handle());
    if (stat == null || !stat.regular || stat.links != 1 || !stat.identity.equals(file.identity())) {
      return StatusCode.CONFLICT;
    }
    if (replace) {
      StatusCode check = validateTarget(targetName);
      if (!check.isOk()) return check;
    }
    if (WindowsFileBridge.rename(file.handle(), handle, targetName, replace) == 0) {
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    }
    result.set(null, DirectoryDurability.UNKNOWN);
    return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
  }

  @Override
  public StatusCode publishDirectoryExclusive(RiverDirectory sourceParent, RiverDirectory stage,
      String stageName, String targetName, DirectoryOperationResult result) {
    if (result == null || !(sourceParent instanceof WindowsRiverDirectory source)
        || !(stage instanceof WindowsRiverDirectory staged)
        || !RiverDirectoryNames.validWindows(stageName)
        || !RiverDirectoryNames.validWindows(targetName)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    synchronized (CROSS_PARENT) {
      synchronized (this) {
        synchronized (source) {
          if (!admission().isOk()) return admission();
          if (!source.admission().isOk()) return source.admission();
          WindowsDirectoryInspection.Stat stat = WindowsDirectoryInspection.inspect(staged.handle);
          if (stat == null || !stat.directory || stat.reparse || !stat.identity.equals(staged.identity)) {
            return StatusCode.CONFLICT;
          }
          if (WindowsFileBridge.rename(staged.handle, handle, targetName, false) != 0) {
            result.set(null, DirectoryDurability.UNKNOWN);
            return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
          }
          StatusCode targetForce = force(new DirectoryOperationResult());
          StatusCode sourceForce = source.force(new DirectoryOperationResult());
          if (targetForce.isOk() && sourceForce.isOk()) {
            result.set(null, DirectoryDurability.DURABLE);
            return StatusCode.OK;
          }
          result.set(null, DirectoryDurability.UNKNOWN);
          return !targetForce.isOk() ? targetForce : sourceForce;
        }
      }
    }
  }

  @Override
  public synchronized StatusCode removeOwned(String name, FileIdentity expected,
      DirectoryOperationResult result) {
    if (result == null || expected == null || !begin(name)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    MemorySegment child = WindowsFileBridge.openAt(handle, name, false, false);
    if (child.equals(MemorySegment.NULL)) child = WindowsFileBridge.openAt(handle, name, true, false);
    if (child.equals(MemorySegment.NULL)) return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
    FileIdentity identity = WindowsFileBridge.identity(child);
    if (identity == null || !identity.equals(expected)) {
      WindowsFileBridge.close(child);
      return StatusCode.CONFLICT;
    }
    int remove = WindowsFileBridge.remove(child);
    int removeStatus = WindowsNativeBindings.status();
    int close = WindowsFileBridge.close(child);
    if (remove != 0) return WindowsRiverDaemonFileSystem.status(removeStatus);
    if (close != 0) return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
    result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!admission().isOk()) return admission();
    if (WindowsFileBridge.force(handle) == 0) {
      result.set(null, DirectoryDurability.DURABLE);
      return StatusCode.OK;
    }
    result.set(null, DirectoryDurability.UNKNOWN);
    return WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    return WindowsFileBridge.close(handle) == 0 ? StatusCode.OK
        : WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
  }

  private StatusCode validateTarget(String name) {
    MemorySegment target = WindowsFileBridge.openAt(handle, name, false, false);
    if (target.equals(MemorySegment.NULL)) {
      return WindowsNativeBindings.status() == WindowsFileBridge.STATUS_OBJECT_NAME_NOT_FOUND
          ? StatusCode.OK : WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status());
    }
    WindowsDirectoryInspection.Stat stat = WindowsDirectoryInspection.inspect(target);
    StatusCode check = stat == null ? WindowsRiverDaemonFileSystem.status(WindowsNativeBindings.status())
        : WindowsDirectoryInspection.verifyFile(target, stat);
    WindowsFileBridge.close(target);
    return check;
  }

  private StatusCode admission() { return closed ? StatusCode.CLOSED : StatusCode.OK; }
  private boolean begin(String name) { return admission().isOk() && RiverDirectoryNames.validWindows(name); }
}
