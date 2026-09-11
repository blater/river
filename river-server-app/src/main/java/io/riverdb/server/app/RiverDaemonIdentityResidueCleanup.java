package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.*;
import java.util.Arrays;

final class RiverDaemonIdentityResidueCleanup {
  private static final int MAX_RECORD_BYTES = RiverDaemonIdentityRecords.MAX_RECORD_BYTES;
  private RiverDaemonIdentityResidueCleanup() {}

  static StatusCode cleanupCommittedResidue(RiverDaemonIdentity.IdentityResult result) {
    if (result == null || result.directory() == null || result.lock() == null
        || result.incarnation() == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDirectory directory = result.directory();
    DirectoryListResult entries = new DirectoryListResult(16);
    StatusCode status = directory.list(entries);
    if (!status.isOk()) return status;
    if (!RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.INSTANCE_FILE)) {
      return StatusCode.CONFLICT;
    }
    if (!RiverDaemonIdentityNamespace.hasEntry(entries, "bootstrap.properties")) {
      return unknownResidue(entries) ? StatusCode.CORRUPTION : StatusCode.OK;
    }
    Residue residue = Residue.read(directory, entries, result);
    if (!residue.status.isOk()) return residue.status;
    return removeResidue(directory, residue);
  }

  private static boolean unknownResidue(DirectoryListResult entries) {
    for (int index = 0; index < entries.size(); index++) {
      if (RiverDaemonIdentityNamespace.isIdentityResidueEntry(entries.name(index))) return true;
    }
    return false;
  }

  private static StatusCode removeResidue(RiverDirectory directory, Residue residue) {
    StatusCode status = StatusCode.OK;
    if (residue.instanceIdentity != null) status = directory.removeOwned(
        residue.instanceStageName, residue.instanceIdentity, new DirectoryOperationResult());
    if (status.isOk() && residue.stagingIdentity != null) status = directory.removeOwned(
        residue.stagingName, residue.stagingIdentity, new DirectoryOperationResult());
    if (status.isOk() && (residue.instanceIdentity != null || residue.stagingIdentity != null)) {
      status = RiverDaemonIdentityFiles.forceDirectory(directory);
    }
    if (status.isOk()) status = directory.removeOwned(
        "bootstrap.properties", residue.bootstrapIdentity, new DirectoryOperationResult());
    return status.isOk() ? RiverDaemonIdentityFiles.forceDirectory(directory) : status;
  }

  private static final class Inspection {
    private final StatusCode status;
    private final FileIdentity identity;
    private Inspection(StatusCode status, FileIdentity identity) {
      this.status = status;
      this.identity = identity;
    }
  }

  private static final class Residue {
    private final StatusCode status;
    private final FileIdentity bootstrapIdentity;
    private final FileIdentity stagingIdentity;
    private final FileIdentity instanceIdentity;
    private final String stagingName;
    private final String instanceStageName;
    private Residue(StatusCode status) {
      this(status, null, null, null, null, null);
    }
    private Residue(StatusCode status, FileIdentity bootstrapIdentity, FileIdentity stagingIdentity,
        FileIdentity instanceIdentity, String stagingName, String instanceStageName) {
      this.status = status; this.bootstrapIdentity = bootstrapIdentity;
      this.stagingIdentity = stagingIdentity; this.instanceIdentity = instanceIdentity;
      this.stagingName = stagingName; this.instanceStageName = instanceStageName;
    }

    private static Residue read(RiverDirectory directory, DirectoryListResult entries,
        RiverDaemonIdentity.IdentityResult result) {
      RiverFileResult fileResult = new RiverFileResult();
      StatusCode status = directory.openFile("bootstrap.properties", RiverOpenMode.EXISTING,
          fileResult);
      if (!status.isOk()) return new Residue(status);
      RiverFile file = fileResult.file();
      FileIdentity bootstrapIdentity = file.identity();
      byte[] bytes = new byte[MAX_RECORD_BYTES];
      status = RiverDaemonIdentityFiles.readRecord(file, bytes, "bootstrap.properties");
      RiverDaemonIdentityRecords.BootstrapRecord bootstrap = status.isOk()
          ? RiverDaemonIdentityRecords.parseBootstrap(bytes) : null;
      Arrays.fill(bytes, (byte) 0);
      StatusCode close = file.close();
      if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      if (status.isOk()) status = validateBootstrap(bootstrap, bootstrapIdentity, result);
      if (status.isOk()) status = validateEntries(entries, bootstrap);
      if (!status.isOk()) return new Residue(status);
      Inspection staging = inspectStaging(directory, entries, bootstrap);
      if (!staging.status.isOk()) return new Residue(staging.status);
      Inspection instance = inspectInstance(directory, entries, bootstrap, result);
      if (!instance.status.isOk()) return new Residue(instance.status);
      return new Residue(StatusCode.OK, bootstrapIdentity, staging.identity, instance.identity,
          bootstrap.stagingName, bootstrap.instanceStageName);
    }

    private static StatusCode validateBootstrap(
        RiverDaemonIdentityRecords.BootstrapRecord bootstrap, FileIdentity identity,
        RiverDaemonIdentity.IdentityResult result) {
      return bootstrap != null && identity != null
          && bootstrap.high == result.incarnation().high()
          && bootstrap.low == result.incarnation().low()
          && bootstrap.stagingName.equals(".riverd-bootstrap-" + bootstrap.nonce)
          && bootstrap.instanceStageName.equals(".instance-" + bootstrap.nonce + ".stage")
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }

    private static StatusCode validateEntries(DirectoryListResult entries,
        RiverDaemonIdentityRecords.BootstrapRecord bootstrap) {
      String[] allowed = {RiverDaemonIdentity.LOCK_FILE, RiverDaemonIdentity.INSTANCE_FILE,
          RiverDaemonIdentity.DATABASE_NAME, RiverDaemonIdentity.SECURITY_NAME,
          "bootstrap.properties", bootstrap.stagingName, bootstrap.instanceStageName};
      for (int index = 0; index < entries.size(); index++) {
        boolean known = false;
        for (String name : allowed) known |= name.equals(entries.name(index));
        if (!known && !RiverDaemonIdentityNamespace.isLifecycleEntry(entries.name(index))) {
          return StatusCode.CORRUPTION;
        }
      }
      return StatusCode.OK;
    }

    private static Inspection inspectStaging(RiverDirectory directory,
        DirectoryListResult entries, RiverDaemonIdentityRecords.BootstrapRecord bootstrap) {
      if (!RiverDaemonIdentityNamespace.hasEntry(entries, bootstrap.stagingName)) {
        return new Inspection(StatusCode.OK, null);
      }
      RiverDirectoryResult result = new RiverDirectoryResult();
      StatusCode status = directory.openDirectory(bootstrap.stagingName, result);
      if (!status.isOk()) return new Inspection(status, null);
      RiverDirectory staging = result.directory();
      FileIdentity identity = staging.identity();
      DirectoryListResult children = new DirectoryListResult(8);
      status = staging.list(children);
      StatusCode close = staging.close();
      if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      if (!status.isOk()) return new Inspection(status, null);
      return identity != null && children.size() == 0
          ? new Inspection(StatusCode.OK, identity) : new Inspection(StatusCode.CORRUPTION, null);
    }

    private static Inspection inspectInstance(RiverDirectory directory,
        DirectoryListResult entries, RiverDaemonIdentityRecords.BootstrapRecord bootstrap,
        RiverDaemonIdentity.IdentityResult result) {
      if (!RiverDaemonIdentityNamespace.hasEntry(entries, bootstrap.instanceStageName)) {
        return new Inspection(StatusCode.OK, null);
      }
      RiverFileResult fileResult = new RiverFileResult();
      StatusCode status = directory.openFile(bootstrap.instanceStageName,
          RiverOpenMode.EXISTING, fileResult);
      if (!status.isOk()) return new Inspection(status, null);
      RiverFile file = fileResult.file();
      FileIdentity identity = file.identity();
      byte[] bytes = new byte[MAX_RECORD_BYTES];
      status = RiverDaemonIdentityFiles.readRecord(file, bytes, bootstrap.instanceStageName);
      RiverDaemonIdentityRecords.InstanceRecord instance = status.isOk()
          ? RiverDaemonIdentityRecords.parseInstance(bytes) : null;
      Arrays.fill(bytes, (byte) 0);
      StatusCode close = file.close();
      if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      if (!status.isOk()) return new Inspection(status, null);
      return identity != null && instance != null
          && instance.incarnation.high() == result.incarnation().high()
          && instance.incarnation.low() == result.incarnation().low()
          ? new Inspection(StatusCode.OK, identity) : new Inspection(StatusCode.CORRUPTION, null);
    }
  }
}
