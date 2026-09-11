package io.riverdb.platform.riverd.apfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class ApfsRiverDirectory implements RiverDirectory {
  /* Serializes cross-parent operations before either directory monitor is acquired. */
  private static final Object CROSS_PARENT_OPERATION = new Object();

  private final int fd;
  private final FileIdentity identity;
  private boolean closed;

  ApfsRiverDirectory(int descriptor, FileIdentity stableIdentity) {
    fd = descriptor;
    identity = stableIdentity;
  }

  int fd() {
    return fd;
  }

  @Override
  public FileIdentity identity() {
    return identity;
  }

  @Override
  public synchronized StatusCode createDirectory(String childName, RiverDirectoryResult result) {
    if (!begin(childName) || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int status = DarwinNamespaceBridge.mkdirAt(fd, childName, 0700);
    if (status != 0) return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
    int child = DarwinFileBridge.openAt(fd, childName, directoryFlags(), 0);
    if (child < 0) {
      return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
    }
    DarwinNamespaceBridge.NativeStat stat = DarwinNamespaceBridge.stat(child);
    if (stat == null) {
      StatusCode error = ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
      DarwinFileBridge.close(child);
      return error;
    }
    StatusCode verify = ApfsRiverDaemonFileSystem.verifyPrivateDirectory(child, stat);
    if (!verify.isOk()) {
      DarwinFileBridge.close(child);
      return removeCreated(childName, stat.identity, verify);
    }
    result.set(new ApfsRiverDirectory(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode openDirectory(String childName, RiverDirectoryResult result) {
    if (!begin(childName) || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int child = DarwinFileBridge.openAt(fd, childName, directoryFlags(), 0);
    if (child < 0) return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
    DarwinNamespaceBridge.NativeStat stat = DarwinNamespaceBridge.stat(child);
    StatusCode verify = ApfsRiverDaemonFileSystem.verifyPrivateDirectory(child, stat);
    if (!verify.isOk()) {
      DarwinFileBridge.close(child);
      return verify;
    }
    result.set(new ApfsRiverDirectory(child, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode openFile(
      String childName, RiverOpenMode mode, RiverFileResult result) {
    if (!begin(childName) || mode == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int flags = DarwinFileBridge.O_RDWR | DarwinFileBridge.O_CLOEXEC | DarwinFileBridge.O_NOFOLLOW;
    if (mode == RiverOpenMode.CREATE_NEW) flags |= DarwinFileBridge.O_CREAT | DarwinFileBridge.O_EXCL;
    int child = DarwinFileBridge.openAt(fd, childName, flags, 0600);
    if (child < 0) return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
    DarwinNamespaceBridge.NativeStat stat = DarwinNamespaceBridge.stat(child);
    if (stat == null) {
      StatusCode error = ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
      DarwinFileBridge.close(child);
      return error;
    }
    StatusCode verify = ApfsRiverDaemonFileSystem.verifyFile(child, stat);
    if (!verify.isOk()) {
      DarwinFileBridge.close(child);
      return mode == RiverOpenMode.CREATE_NEW
          ? removeCreated(childName, stat.identity, verify) : verify;
    }
    result.set(new ApfsRiverFile(child, stat.identity));
    return StatusCode.OK;
  }

  /* Native enumeration opens an independent description through the verified parent descriptor. */
  @SuppressWarnings("restricted")
  @Override
  public synchronized StatusCode list(DirectoryListResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    int streamFd = DarwinFileBridge.openAt(fd, ".", directoryFlags(), 0);
    if (streamFd < 0) return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
    MemorySegment directory = DarwinNamespaceBridge.openDirectoryStream(streamFd);
    if (directory.equals(MemorySegment.NULL)) {
      int error = DarwinNativeBindings.errno();
      DarwinFileBridge.close(streamFd);
      return ApfsRiverDaemonFileSystem.status(error);
    }
    StatusCode scanStatus = StatusCode.IO_FAILURE;
    try {
      scanStatus = scan(directory, result);
    } finally {
      int closeStatus = DarwinNamespaceBridge.closeDirectoryStream(directory);
      if (scanStatus.isOk() && closeStatus != 0) {
        scanStatus = ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
      }
    }
    return scanStatus;
  }

  @SuppressWarnings("restricted")
  private StatusCode scan(MemorySegment directory, DirectoryListResult result) {
    try (Arena entries = Arena.ofConfined()) {
      MemorySegment entryBuffer = entries.allocate(1048, 8);
      MemorySegment resultPointer = entries.allocate(ValueLayout.ADDRESS);
      while (true) {
        int readStatus = DarwinNamespaceBridge.readDirectory(directory, entryBuffer, resultPointer);
        if (readStatus != 0) return ApfsRiverDaemonFileSystem.status(readStatus);
        MemorySegment entry = resultPointer.get(ValueLayout.ADDRESS, 0);
        if (entry.equals(MemorySegment.NULL)) {
          result.finish(1);
          return StatusCode.OK;
        }
        MemorySegment bytes = entry.reinterpret(1048);
        int length = Short.toUnsignedInt(bytes.get(ValueLayout.JAVA_SHORT, 16));
        int nameLength = Short.toUnsignedInt(bytes.get(ValueLayout.JAVA_SHORT, 18));
        if (length < 21 || length > 1048 || nameLength < 1 || nameLength > 1023
            || 21 + nameLength > length) {
          return StatusCode.CORRUPTION;
        }
        String name = new String(
            bytes.asSlice(21, nameLength).toArray(ValueLayout.JAVA_BYTE),
            java.nio.charset.StandardCharsets.UTF_8);
        if (name.equals(".") || name.equals("..")) continue;
        DarwinNamespaceBridge.NativeStat stat = DarwinNamespaceBridge.statAt(fd, name);
        if (stat == null) return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
        DirectoryEntryType type;
        if (stat.regularFile()) type = DirectoryEntryType.FILE;
        else if (stat.directory()) type = DirectoryEntryType.DIRECTORY;
        else return StatusCode.CORRUPTION;
        StatusCode added = result.add(name, type);
        if (!added.isOk()) return added;
      }
    }
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

  @Override
  public StatusCode publishDirectoryExclusive(
      RiverDirectory sourceParent,
      RiverDirectory stage,
      String stageName,
      String targetName,
      DirectoryOperationResult result) {
    if (result == null || !(sourceParent instanceof ApfsRiverDirectory source)
        || !(stage instanceof ApfsRiverDirectory stageDirectory)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!validChild(stageName) || !validChild(targetName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    synchronized (CROSS_PARENT_OPERATION) {
      synchronized (this) {
        synchronized (source) {
          StatusCode admission = admission();
          if (!admission.isOk()) return admission;
          StatusCode sourceAdmission = source.admission();
          if (!sourceAdmission.isOk()) return sourceAdmission;
          DarwinNamespaceBridge.NativeStat stageStat = DarwinNamespaceBridge.statAt(source.fd, stageName);
          if (stageStat == null || !stageStat.directory()
              || !stageStat.identity.equals(stageDirectory.identity)) return StatusCode.CONFLICT;
          int status = DarwinNamespaceBridge.renameExclusive(
              source.fd, stageName, fd, targetName);
          if (status == 0) {
            int sourceForce = DarwinNamespaceBridge.forceDirectory(source.fd);
            int targetForce = sourceForce == 0 ? DarwinNamespaceBridge.forceDirectory(fd) : -1;
            if (sourceForce == 0 && targetForce == 0) {
              result.set(null, DirectoryDurability.DURABLE);
              return StatusCode.OK;
            }
            result.set(null, DirectoryDurability.UNKNOWN);
            return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
          }
          int error = DarwinNativeBindings.errno();
          result.set(null, error == DarwinNamespaceBridge.EEXIST
              ? DirectoryDurability.NOT_APPLIED : DirectoryDurability.UNKNOWN);
          return ApfsRiverDaemonFileSystem.status(error);
        }
      }
    }
  }

  private StatusCode publish(
      RiverFile stage,
      String stageName,
      String targetName,
      DirectoryOperationResult result,
      boolean exclusive) {
    if (result == null || !(stage instanceof ApfsRiverFile stageFile)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (!validChild(stageName) || !validChild(targetName) || stageName.equals(targetName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    DarwinNamespaceBridge.NativeStat stageStat = DarwinNamespaceBridge.statAt(fd, stageName);
    if (stageStat == null || !stageStat.regularFile()
        || stageStat.links != 1 || !stageStat.identity.equals(stageFile.identity())) {
      return StatusCode.CONFLICT;
    }
    if (!exclusive) {
      StatusCode targetStatus = validateReplacementTarget(targetName);
      if (!targetStatus.isOk()) return targetStatus;
    }
    int status = exclusive
        ? DarwinNamespaceBridge.renameExclusive(fd, stageName, targetName)
        : DarwinNamespaceBridge.renameReplace(fd, stageName, targetName);
    if (status == 0) {
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    }
    int error = DarwinNativeBindings.errno();
    result.set(null, error == DarwinNamespaceBridge.EEXIST
        ? DirectoryDurability.NOT_APPLIED : DirectoryDurability.UNKNOWN);
    return ApfsRiverDaemonFileSystem.status(error);
  }

  @Override
  public synchronized StatusCode removeOwned(
      String childName, FileIdentity expectedIdentity, DirectoryOperationResult result) {
    if (result == null || expectedIdentity == null || !begin(childName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DarwinNamespaceBridge.NativeStat stat = DarwinNamespaceBridge.statAt(fd, childName);
    if (stat == null) return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
    if (!stat.regularFile() && !stat.directory()) return StatusCode.CONFLICT;
    if (!stat.identity.equals(expectedIdentity)) return StatusCode.CONFLICT;
    int status = stat.directory()
        ? DarwinNamespaceBridge.removeDirectory(fd, childName)
        : DarwinNamespaceBridge.remove(fd, childName);
    if (status == 0) {
      result.set(null, DirectoryDurability.VISIBLE_NOT_DURABLE);
      return StatusCode.OK;
    }
    int error = DarwinNativeBindings.errno();
    result.set(null, DirectoryDurability.UNKNOWN);
    return ApfsRiverDaemonFileSystem.status(error);
  }

  @Override
  public synchronized StatusCode force(DirectoryOperationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) return admission;
    int status = DarwinNamespaceBridge.forceDirectory(fd);
    if (status == 0) {
      result.set(null, DirectoryDurability.DURABLE);
      return StatusCode.OK;
    }
    result.set(null, DirectoryDurability.UNKNOWN);
    return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
  }

  @Override
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    closed = true;
    return DarwinFileBridge.close(fd) == 0
        ? StatusCode.OK : ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
  }

  private StatusCode admission() {
    return closed ? StatusCode.CLOSED : StatusCode.OK;
  }

  private StatusCode removeCreated(String childName, FileIdentity identity, StatusCode primary) {
    DirectoryOperationResult cleanup = new DirectoryOperationResult();
    StatusCode removed = removeOwned(childName, identity, cleanup);
    if (!removed.isOk()) return removed;
    DirectoryOperationResult forced = new DirectoryOperationResult();
    StatusCode forceStatus = force(forced);
    return forceStatus.isOk() ? primary : forceStatus;
  }

  private StatusCode validateReplacementTarget(String targetName) {
    int target = DarwinFileBridge.openAt(fd, targetName, fileFlags(), 0);
    if (target < 0) {
      int error = DarwinNativeBindings.errno();
      return error == DarwinNamespaceBridge.ENOENT
          ? StatusCode.OK : ApfsRiverDaemonFileSystem.status(error);
    }
    DarwinNamespaceBridge.NativeStat stat = DarwinNamespaceBridge.stat(target);
    if (stat == null) {
      int error = DarwinNativeBindings.errno();
      DarwinFileBridge.close(target);
      return ApfsRiverDaemonFileSystem.status(error);
    }
    StatusCode check = ApfsRiverDaemonFileSystem.verifyFile(target, stat);
    if (!check.isOk()) {
      DarwinFileBridge.close(target);
      return check;
    }
    int close = DarwinFileBridge.close(target);
    if (close != 0) return ApfsRiverDaemonFileSystem.status(DarwinNativeBindings.errno());
    return StatusCode.OK;
  }

  private boolean begin(String name) {
    return admission().isOk() && validChild(name);
  }

  private static int directoryFlags() {
    return DarwinFileBridge.O_RDONLY | DarwinFileBridge.O_DIRECTORY
        | DarwinFileBridge.O_CLOEXEC | DarwinFileBridge.O_NOFOLLOW;
  }

  private static int fileFlags() {
    return DarwinFileBridge.O_RDWR | DarwinFileBridge.O_CLOEXEC | DarwinFileBridge.O_NOFOLLOW;
  }

  private static boolean validChild(String name) {
    if (name == null || name.isBlank() || name.length() > 255 || name.equals(".")
        || name.equals("..")) return false;
    for (int index = 0; index < name.length(); index++) {
      char value = name.charAt(index);
      if (value == '/' || value == '\\' || value == 0 || value == '\r' || value == '\n'
          || Character.getType(value) == Character.CONTROL) return false;
    }
    return true;
  }
}
