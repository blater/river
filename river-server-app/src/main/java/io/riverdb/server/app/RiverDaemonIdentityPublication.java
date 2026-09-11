package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.*;
import io.riverdb.platform.riverd.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

final class RiverDaemonIdentityPublication {
  private static final int MAX_RECORD_BYTES = RiverDaemonIdentityRecords.MAX_RECORD_BYTES;
  private RiverDaemonIdentityPublication() {}
  static StatusCode completeCreate(RiverDaemonIdentity.IdentityResult result) {
    if (result == null || !result.readyForPublication()) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = RiverDaemonIdentityRestart.handoffOwner(result);
    if (status.isOk()) status = repairStage(result);
    if (status.isOk()) status = publishComponents(result);
    if (status.isOk()) status = publishInstance(result);
    return status.isOk() ? finishCommit(result) : status;
  }

  private static StatusCode repairStage(RiverDaemonIdentity.IdentityResult result) {
    if (result.stageRepairIdentity() == null) return StatusCode.OK;
    StatusCode status = RiverDaemonIdentityFiles.removeOwned(
        result.directory(), result.stageRepairName(), result.stageRepairIdentity());
    if (status.isOk()) status = RiverDaemonIdentityFiles.forceDirectory(result.directory());
    if (status.isOk()) result.clearStageRepair();
    return status;
  }

  private static StatusCode publishComponents(RiverDaemonIdentity.IdentityResult result) {
    StatusCode status = StatusCode.OK;
    if (!result.databasePublished()) {
      status = RiverDaemonIdentityFiles.publishDirectory(
          result.directory(), result.staging(), result.database(), RiverDaemonIdentity.DATABASE_NAME);
      if (status.isOk()) result.markDatabasePublished();
    }
    if (status.isOk() && !result.securityPublished()) {
      status = RiverDaemonIdentityFiles.publishDirectory(
          result.directory(), result.staging(), result.security(), RiverDaemonIdentity.SECURITY_NAME);
      if (status.isOk()) result.markSecurityPublished();
    }
    return status;
  }

  private static StatusCode publishInstance(RiverDaemonIdentity.IdentityResult result) {
    RiverDirectory directory = result.directory();
    String stageName = ".instance-" + result.nonce() + ".stage";
    DirectoryListResult entries = new DirectoryListResult(16);
    StatusCode status = directory.list(entries);
    boolean existed = status.isOk() && RiverDaemonIdentityNamespace.hasEntry(entries, stageName);
    RiverFileResult stageResult = new RiverFileResult();
    if (status.isOk()) status = directory.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
    if (status == StatusCode.CONFLICT) {
      status = directory.openFile(stageName, RiverOpenMode.EXISTING, stageResult);
      if (status.isOk()) status = validateInstance(stageResult.file(), stageName, result);
    }
    if (!status.isOk()) return status;
    RiverFile stage = stageResult.file();
    if (!existed) {
      String body = RiverDaemonIdentityRecords.record(List.of(
          "format=" + RiverDaemonIdentityRecords.INSTANCE_FORMAT,
          "database-incarnation-high=" + result.incarnation().high(),
          "database-incarnation-low=" + result.incarnation().low(),
          "initial-wal-generation=1"));
      status = RiverDaemonIdentityFiles.write(stage, body.getBytes(StandardCharsets.UTF_8));
    }
    if (status.isOk()) status = directory.publishExclusive(
        stage, stageName, RiverDaemonIdentity.INSTANCE_FILE, new DirectoryOperationResult());
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk()) status = closeStatus;
    return status.isOk() ? RiverDaemonIdentityFiles.forceDirectory(directory) : status;
  }

  private static StatusCode validateInstance(
      RiverFile file, String name, RiverDaemonIdentity.IdentityResult result) {
    byte[] bytes = new byte[MAX_RECORD_BYTES];
    StatusCode status = RiverDaemonIdentityFiles.readRecord(file, bytes, name);
    RiverDaemonIdentityRecords.InstanceRecord parsed = status.isOk()
        ? RiverDaemonIdentityRecords.parseInstance(bytes) : null;
    Arrays.fill(bytes, (byte) 0);
    return status.isOk() && (parsed == null
        || parsed.incarnation.high() != result.incarnation().high()
        || parsed.incarnation.low() != result.incarnation().low())
        ? StatusCode.CORRUPTION : status;
  }

  private static StatusCode finishCommit(RiverDaemonIdentity.IdentityResult result) {
    StatusCode status = RiverDaemonIdentityFiles.removeOwned(
        result.directory(), ".riverd-bootstrap-" + result.nonce(), result.staging().identity());
    if (status.isOk()) status = RiverDaemonIdentityFiles.forceDirectory(result.directory());
    if (status.isOk()) status = RiverDaemonIdentityFiles.removeOwned(
        result.directory(), "bootstrap.properties", null);
    return status.isOk() ? RiverDaemonIdentityFiles.forceDirectory(result.directory()) : status;
  }




}
