package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import java.sql.SQLException;
import java.util.Arrays;

/** Grows one result's text scratch only to the width it actually reaches. */
final class RiverJdbcTextScratch {
  static final char[] EMPTY = new char[0];
  static final int TEMPORAL_CHARACTERS = 64;

  private RiverJdbcTextScratch() { }

  static char[] require(char[] current, int required) throws SQLException {
    if (required < 0 || required > CommandResult.MAXIMUM_TEXT_CHARACTERS) {
      throw JdbcExceptions.invalid("text result exceeds the JDBC scratch bound");
    }
    if (required <= current.length) return current;
    int capacity = Math.min(
        CommandResult.MAXIMUM_TEXT_CHARACTERS,
        Math.max(required, Math.max(TEMPORAL_CHARACTERS, current.length << 1)));
    try {
      return Arrays.copyOf(current, capacity);
    } catch (OutOfMemoryError failure) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "grow text result scratch");
    }
  }
}
