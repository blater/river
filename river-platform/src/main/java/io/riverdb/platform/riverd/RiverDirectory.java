package io.riverdb.platform.riverd;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;

/** A directory capability whose children are accessed descriptor-relatively. */
public interface RiverDirectory {
  FileIdentity identity();

  StatusCode createDirectory(String childName, RiverDirectoryResult result);

  StatusCode openDirectory(String childName, RiverDirectoryResult result);

  StatusCode openFile(String childName, RiverOpenMode mode, RiverFileResult result);

  default StatusCode createFile(String childName, RiverFileResult result) {
    return openFile(childName, RiverOpenMode.CREATE_NEW, result);
  }

  default StatusCode createTemporary(String childName, RiverFileResult result) {
    return createFile(childName, result);
  }

  StatusCode list(DirectoryListResult result);

  StatusCode publishExclusive(
      RiverFile stage,
      String stageName,
      String targetName,
      DirectoryOperationResult result);

  StatusCode publishReplacement(
      RiverFile stage,
      String stageName,
      String targetName,
      DirectoryOperationResult result);

  /** Moves a verified staged directory from another verified parent without replacement. */
  StatusCode publishDirectoryExclusive(
      RiverDirectory sourceParent,
      RiverDirectory stage,
      String stageName,
      String targetName,
      DirectoryOperationResult result);

  /**
   * Removes a direct child after revalidating its expected identity.
   *
   * <p>The check and unlink are one descriptor-relative adapter operation. POSIX does not provide
   * an unlink-by-file-key primitive; the adapter therefore does not claim atomic identity fencing
   * against same-account namespace writers, which are outside the accepted threat boundary.
   */
  StatusCode removeOwned(
      String childName,
      FileIdentity expectedIdentity,
      DirectoryOperationResult result);

  StatusCode force(DirectoryOperationResult result);

  StatusCode close();
}
