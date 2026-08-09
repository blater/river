package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Durable namespace operations rooted at one validated database directory.
 *
 * <p>A non-OK status does not imply that a mutation was absent. Callers must inspect the
 * caller-owned result and recover when its durability is {@link DirectoryDurability#UNKNOWN}.
 */
public interface DurableDirectory {
  StatusCode createTemporary(String temporaryFileName, DirectoryOperationResult result);

  StatusCode replace(
      String temporaryFileName,
      String destinationFileName,
      DirectoryOperationResult result);

  StatusCode force(DirectoryOperationResult result);

  StatusCode reopen(String fileName, DirectoryOperationResult result);
}
