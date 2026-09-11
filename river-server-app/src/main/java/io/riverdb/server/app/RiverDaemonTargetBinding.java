package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.file.Path;

/** Validates instance and runtime records against one target owner. */
final class RiverDaemonTargetBinding {
  private RiverDaemonTargetBinding() { }

  static StatusCode verifyInstance(
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

  static BoundRuntime readBoundRuntime(
      RiverDaemonFileSystem filesystem, Path runtimeRoot, Path datadir,
      RiverDaemonIdentityRecords.LockRecord owner) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = RiverDaemonRuntimeStorage.openRuntime(
        filesystem, runtimeRoot, datadir.toString(), result);
    if (status == StatusCode.CONFLICT) return BoundRuntime.missing();
    if (!status.isOk()) return BoundRuntime.failure(status);
    RiverFile file = result.file();
    FileIdentity identity = file.identity();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return BoundRuntime.failure(read.status);
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return BoundRuntime.failure(closeStatus);
    }
    RiverDaemonRuntimeModel.RuntimeRecord runtime =
        RiverDaemonRuntimeCodec.parseRuntime(read.bytes);
    DatabaseIncarnation incarnation = DatabaseIncarnation.of(owner.high, owner.low);
    if (runtime == null || identity == null
        || !runtime.matches(datadir.toString(), incarnation, owner)) {
      return BoundRuntime.failure(StatusCode.CORRUPTION);
    }
    return new BoundRuntime(StatusCode.OK, runtime, identity);
  }

  static boolean sameOwner(
      RiverDaemonIdentityRecords.LockRecord first,
      RiverDaemonIdentityRecords.LockRecord second) {
    return first.datadir.equals(second.datadir) && first.high == second.high
        && first.low == second.low && first.pid == second.pid && first.start == second.start
        && first.nonce.equals(second.nonce);
  }

  static final class BoundRuntime {
    final StatusCode status;
    final RiverDaemonRuntimeModel.RuntimeRecord record;
    final FileIdentity identity;

    BoundRuntime(StatusCode status, RiverDaemonRuntimeModel.RuntimeRecord record,
        FileIdentity identity) {
      this.status = status;
      this.record = record;
      this.identity = identity;
    }

    static BoundRuntime missing() {
      return new BoundRuntime(StatusCode.OK, null, null);
    }

    static BoundRuntime failure(StatusCode status) {
      return new BoundRuntime(status, null, null);
    }
  }
}
