package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Durable namespace operations rooted at one validated database directory.
 *
 * <p>Operations are synchronous: they never return an applied-but-completion-pending state.
 * Asynchronous installation and authenticated polling belong to {@link AtomicFileInstaller}.
 *
 * <p>This Phase 0 surface is deliberately the atomic-file-install subset. General child-directory
 * creation/listing, non-install rename, deletion, and named truncation remain an open P08 contract
 * gap; callers must not infer those protocols from {@link #replace} or {@link #force}.
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
