package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverLock;
import io.riverdb.platform.riverd.RiverLockResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.file.Path;
import java.util.Objects;

/** One capability-backed, owner-verified River instance target. */
final class RiverDaemonTarget {
  RiverDirectory directory;
  RiverFile lockFile;
  RiverDaemonIdentityRecords.LockRecord owner;
  RiverDaemonRuntimeModel.RuntimeRecord runtime;
  FileIdentity runtimeIdentity;
  String runtimeChecksum;
  Path datadir;
  private Path runtimeRoot;

  private RiverDaemonFileSystem filesystem;
  private FileIdentity lockIdentity;
  private boolean closed;

  static StatusCode open(
      RiverDaemonFileSystem filesystem, Path datadir, Path runtimeRoot, Result result) {
    if (result == null || filesystem == null || datadir == null
        || !RiverDaemonIdentityRecords.validDatadir(datadir.toString())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(datadir, directoryResult);
    if (!status.isOk()) return status;
    RiverDirectory directory = directoryResult.directory();
    RiverFileResult lockResult = new RiverFileResult();
    status = directory.openFile(RiverDaemonIdentity.LOCK_FILE, RiverOpenMode.EXISTING, lockResult);
    if (!status.isOk()) return close(directory, status);
    RiverFile lockFile = lockResult.file();
    FileIdentity lockIdentity = lockFile.identity();
    RiverDaemonRuntimeModel.ReadResult lockRead = RiverDaemonRuntimeStorage.read(lockFile);
    RiverDaemonIdentityRecords.LockRecord owner = lockRead.status.isOk()
        ? RiverDaemonIdentityRecords.parseLock(lockRead.bytes) : null;
    if (!lockRead.status.isOk()) status = lockRead.status;
    if (status.isOk() && (owner == null || lockIdentity == null
        || !datadir.toString().equals(owner.datadir))) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = verifyInstance(directory, owner);
    }
    if (!status.isOk()) return close(lockFile, directory, status);

    RuntimeValues runtime = readRuntime(filesystem, runtimeRoot, datadir, owner);
    if (!runtime.status.isOk()) return close(lockFile, directory, runtime.status);
    RiverDaemonTarget target = new RiverDaemonTarget();
    target.filesystem = filesystem;
    target.directory = directory;
    target.lockFile = lockFile;
    target.lockIdentity = lockIdentity;
    target.owner = owner;
    target.runtime = runtime.record;
    target.runtimeIdentity = runtime.identity;
    target.runtimeChecksum = runtime.record == null ? null : runtime.record.checksum;
    target.datadir = datadir;
    target.runtimeRoot = runtimeRoot;
    result.set(target);
    return StatusCode.OK;
  }

  StatusCode openRuntime(RiverFileResult result) {
    return RiverDaemonRuntimeStorage.openRuntime(filesystem, runtimeRoot, datadir.toString(), result);
  }

  StatusCode revalidate(boolean requireRuntime) {
    if (closed || filesystem == null || directory == null || lockFile == null) {
      return StatusCode.CLOSED;
    }
    if (lockIdentity == null || !lockIdentity.equals(lockFile.identity())) {
      return StatusCode.NOT_OWNER;
    }
    RiverDaemonRuntimeModel.ReadResult lockRead = RiverDaemonRuntimeStorage.read(lockFile);
    if (!lockRead.status.isOk()) return lockRead.status;
    RiverDaemonIdentityRecords.LockRecord currentOwner =
        RiverDaemonIdentityRecords.parseLock(lockRead.bytes);
    if (currentOwner == null || !sameOwner(owner, currentOwner)) return StatusCode.NOT_OWNER;
    StatusCode status = verifyInstance(directory, currentOwner);
    if (!status.isOk()) return status == StatusCode.CORRUPTION ? status : StatusCode.NOT_OWNER;
    if (requireRuntime) {
      RuntimeValues current = readRuntime(filesystem, runtimeRoot, datadir, currentOwner);
      if (!current.status.isOk()) {
        return current.status == StatusCode.CORRUPTION
            ? current.status : StatusCode.NOT_OWNER;
      }
      if (current.record == null || runtime == null || runtimeIdentity == null
          || current.identity == null || !runtimeIdentity.equals(current.identity)
          || !Objects.equals(runtimeChecksum, current.record.checksum)) {
        return StatusCode.NOT_OWNER;
      }
    }
    return lockHeld();
  }

  StatusCode lockHeld() {
    if (closed || filesystem == null || lockFile == null) return StatusCode.CLOSED;
    RiverLockResult result = new RiverLockResult();
    StatusCode status = filesystem.acquireExclusive(lockFile, result);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    RiverLock acquired = result.lock();
    StatusCode closeStatus = acquired == null ? StatusCode.CORRUPTION : acquired.close();
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) return closeStatus;
    return StatusCode.NOT_OWNER;
  }

  StatusCode close() {
    if (closed) return StatusCode.OK;
    closed = true;
    StatusCode status = lockFile == null ? StatusCode.OK : lockFile.close();
    if (status == StatusCode.CLOSED) status = StatusCode.OK;
    StatusCode directoryStatus = directory == null ? StatusCode.OK : directory.close();
    if (status.isOk() && !directoryStatus.isOk() && directoryStatus != StatusCode.CLOSED) {
      status = directoryStatus;
    }
    return status;
  }

  private static StatusCode verifyInstance(
      RiverDirectory directory, RiverDaemonIdentityRecords.LockRecord owner) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = directory.openFile(
        RiverDaemonIdentity.INSTANCE_FILE, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return status;
    RiverFile file = result.file();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return read.status;
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) return closeStatus;
    RiverDaemonIdentityRecords.InstanceRecord instance =
        RiverDaemonIdentityRecords.parseInstance(read.bytes);
    return instance != null && instance.incarnation.high() == owner.high
            && instance.incarnation.low() == owner.low
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static RuntimeValues readRuntime(
      RiverDaemonFileSystem filesystem, Path runtimeRoot, Path datadir,
      RiverDaemonIdentityRecords.LockRecord owner) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = RiverDaemonRuntimeStorage.openRuntime(
        filesystem, runtimeRoot, datadir.toString(), result);
    if (status == StatusCode.CONFLICT) return RuntimeValues.missing();
    if (!status.isOk()) return RuntimeValues.failure(status);
    RiverFile file = result.file();
    FileIdentity identity = file.identity();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return RuntimeValues.failure(read.status);
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return RuntimeValues.failure(closeStatus);
    }
    RiverDaemonRuntimeModel.RuntimeRecord runtime =
        RiverDaemonRuntimeCodec.parseRuntime(read.bytes);
    DatabaseIncarnation incarnation = DatabaseIncarnation.of(owner.high, owner.low);
    if (runtime == null || identity == null
        || !runtime.matches(datadir.toString(), incarnation, owner)) {
      return RuntimeValues.failure(StatusCode.CORRUPTION);
    }
    return new RuntimeValues(StatusCode.OK, runtime, identity);
  }

  private static boolean sameOwner(
      RiverDaemonIdentityRecords.LockRecord first,
      RiverDaemonIdentityRecords.LockRecord second) {
    return first.datadir.equals(second.datadir) && first.high == second.high
        && first.low == second.low && first.pid == second.pid && first.start == second.start
        && first.nonce.equals(second.nonce);
  }

  private static StatusCode close(RiverDirectory directory, StatusCode primary) {
    StatusCode closeStatus = directory.close();
    return primary.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED
        ? closeStatus : primary;
  }

  private static StatusCode close(RiverFile file, RiverDirectory directory, StatusCode primary) {
    StatusCode fileStatus = file.close();
    StatusCode directoryStatus = directory.close();
    if (primary.isOk() && !fileStatus.isOk() && fileStatus != StatusCode.CLOSED) {
      primary = fileStatus;
    }
    if (primary.isOk() && !directoryStatus.isOk() && directoryStatus != StatusCode.CLOSED) {
      primary = directoryStatus;
    }
    return primary;
  }

  private static final class RuntimeValues {
    final StatusCode status;
    final RiverDaemonRuntimeModel.RuntimeRecord record;
    final FileIdentity identity;

    RuntimeValues(StatusCode status, RiverDaemonRuntimeModel.RuntimeRecord record,
        FileIdentity identity) {
      this.status = status;
      this.record = record;
      this.identity = identity;
    }

    static RuntimeValues missing() {
      return new RuntimeValues(StatusCode.OK, null, null);
    }

    static RuntimeValues failure(StatusCode status) {
      return new RuntimeValues(status, null, null);
    }
  }

  static final class Result {
    private RiverDaemonTarget target;

    RiverDaemonTarget target() { return target; }

    void reset() { target = null; }

    void set(RiverDaemonTarget value) { target = value; }
  }
}
