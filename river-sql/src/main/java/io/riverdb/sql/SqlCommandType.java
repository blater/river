package io.riverdb.sql;

public enum SqlCommandType {
  CREATE_TABLE,
  CREATE_UNIQUE_INDEX,
  INSERT,
  SELECT,
  SELECT_BY_VALUE,
  SCAN,
  VALUE_SCAN,
  UPDATE,
  DELETE,
  BEGIN,
  COMMIT,
  ROLLBACK,
  CHECKPOINT
}
