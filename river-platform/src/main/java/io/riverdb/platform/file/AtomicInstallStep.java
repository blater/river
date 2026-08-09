package io.riverdb.platform.file;

/** One externally observable install boundary. */
public enum AtomicInstallStep {
  NONE,
  TEMP_CREATE,
  TEMP_WRITE,
  /** Forces temporary-file content and the metadata required to reopen that file. */
  TEMP_FORCE,
  DESTINATION_REPLACE,
  /** Separately forces the parent namespace containing the replacement. */
  PARENT_DIRECTORY_FORCE,
  REOPEN_VERIFY
}
