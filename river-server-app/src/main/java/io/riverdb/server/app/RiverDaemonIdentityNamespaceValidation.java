package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;

final class RiverDaemonIdentityNamespaceValidation {
  private RiverDaemonIdentityNamespaceValidation() {
  }

  static StatusCode validateCurrentOwner(long pid, long start) {
    ProcessHandle current = ProcessHandle.current();
    if (current.pid() != pid) return StatusCode.NOT_OWNER;
    ProcessHandle.Info info = current.info();
    if (info.startInstant().isEmpty()) return StatusCode.FEATURE_NOT_SUPPORTED;
    return info.startInstant().get().toEpochMilli() == start
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  static StatusCode validateStagingNames(RiverDirectory staging,
      boolean databasePublished, boolean securityPublished) {
    DirectoryListResult entries = new DirectoryListResult(8);
    StatusCode status = staging.list(entries);
    if (!status.isOk()) return status;
    for (int index = 0; index < entries.size(); index++) {
      String name = entries.name(index);
      boolean allowed = (!databasePublished && RiverDaemonIdentity.DATABASE_NAME.equals(name))
          || (!securityPublished && RiverDaemonIdentity.SECURITY_NAME.equals(name));
      if (!allowed) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateExistingComponents(RiverDirectory directory, RiverDirectory staging,
      boolean databasePublished, boolean securityPublished) {
    String[] names = {RiverDaemonIdentity.DATABASE_NAME, RiverDaemonIdentity.SECURITY_NAME};
    boolean[] published = {databasePublished, securityPublished};
    for (int index = 0; index < names.length; index++) {
      RiverDirectoryResult result = new RiverDirectoryResult();
      StatusCode status;
      if (published[index]) {
        status = directory.openDirectory(names[index], result);
      } else if (staging != null) {
        status = staging.openDirectory(names[index], result);
        if (status == StatusCode.CONFLICT) continue;
      } else {
        continue;
      }
      if (!status.isOk()) return status;
      status = result.directory().close();
      if (!status.isOk() && status != StatusCode.CLOSED) return status;
    }
    return StatusCode.OK;
  }
}
