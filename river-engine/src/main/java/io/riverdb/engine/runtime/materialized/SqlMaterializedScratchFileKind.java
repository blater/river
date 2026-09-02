package io.riverdb.engine.runtime.materialized;

/** Fixed runtime-generated file names for one materialized store. */
public enum SqlMaterializedScratchFileKind {
  ROWS("data.rows"),
  INDEX("data.index"),
  KEYS("data.keys"),
  RUNS0("data.runs0"),
  RUNS1("data.runs1");

  private static final int COUNT = values().length;
  private final String fileName;

  SqlMaterializedScratchFileKind(String name) {
    fileName = name;
  }

  String fileName() { return fileName; }
  static int count() { return COUNT; }
}
