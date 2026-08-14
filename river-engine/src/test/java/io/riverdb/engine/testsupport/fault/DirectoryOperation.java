package io.riverdb.engine.testsupport.fault;

/** Provider-neutral operation identity for directory contract scripts and traces. */
public enum DirectoryOperation {
  CREATE_DIRECTORY,
  CREATE_FILE,
  LIST,
  RENAME,
  REMOVE,
  TRUNCATE,
  FILE_READ,
  FILE_WRITE,
  FILE_FORCE,
  DIRECTORY_FORCE,
  REOPEN
}
