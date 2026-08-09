package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Durable namespace operations rooted at one validated database directory.
 *
 * <p>Operations are synchronous: they never return an applied-but-completion-pending state.
 * Asynchronous installation and authenticated polling belong to {@link AtomicFileInstaller}.
 *
 * <p>Every name is a validated direct child, never a path. Creation, rename, and removal become
 * namespace-durable only after {@link #force}. A file truncation becomes content/length durable
 * only after forcing the returned file with {@link ForceMode#CONTENT_AND_METADATA}; forcing the
 * directory is not a substitute for forcing file content and length.
 *
 * <p>A non-OK status does not imply that a mutation was absent. Callers must inspect the
 * caller-owned result and recover when its durability is {@link DirectoryDurability#UNKNOWN}.
 */
public interface DurableDirectory {
  StatusCode createDirectory(String childDirectoryName, DirectoryOperationResult result);

  StatusCode createFile(String fileName, DirectoryOperationResult result);

  StatusCode createTemporary(String temporaryFileName, DirectoryOperationResult result);

  StatusCode list(DirectoryListResult result);

  /** Renames without replacement; an existing destination returns {@code CONFLICT}. */
  StatusCode rename(String sourceName, String destinationName, DirectoryOperationResult result);

  StatusCode replace(
      String temporaryFileName,
      String destinationFileName,
      DirectoryOperationResult result);

  /** Removes a file or empty direct child directory. Missing entries return {@code CONFLICT}. */
  StatusCode remove(String entryName, DirectoryOperationResult result);

  /** Truncates an existing named file and returns an open handle for the required file force. */
  StatusCode truncate(String fileName, long sizeBytes, DirectoryOperationResult result);

  /** Publishes preceding namespace mutations; it never publishes unforced file bytes. */
  StatusCode force(DirectoryOperationResult result);

  StatusCode reopen(String fileName, DirectoryOperationResult result);
}
