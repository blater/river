package io.riverdb.platform.file;

/** Stable phases of the same-directory atomic installation protocol. */
public enum AtomicInstallPhase {
  NEW,
  TEMP_CREATED,
  CONTENT_WRITTEN,
  CONTENT_FORCED,
  DESTINATION_REPLACED,
  DIRECTORY_FORCED,
  VERIFIED,
  RECOVERY_REQUIRED
}
