package io.riverdb.platform.file;

/** One externally observable install boundary. */
public enum AtomicInstallStep {
  NONE,
  TEMP_CREATE,
  TEMP_WRITE,
  TEMP_FORCE,
  DESTINATION_REPLACE,
  PARENT_DIRECTORY_FORCE,
  REOPEN_VERIFY
}
