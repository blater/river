package io.riverdb.platform.file;

/** Selects the provider used for file data access. */
public enum FileIoMode {
  POSITIONAL,
  /** Bounded mapped content; metadata is synchronized when the file extent changes. */
  MAPPED
}
