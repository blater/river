package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Bounded quote-aware reader for semicolon-terminated SQL statements. */
final class RiverSqlScriptReader {
  private final InputStreamReader input;
  private final char[] reads = new char[4 * 1024];
  private final char[] statement =
      new char[RiverSqlMain.MAXIMUM_STATEMENT_CHARACTERS];
  private int readOffset;
  private int readLength;
  private int statementLength;
  private boolean quoted;
  private boolean exhausted;
  private boolean available;

  RiverSqlScriptReader(InputStream source) {
    input = new InputStreamReader(source, StandardCharsets.UTF_8);
  }

  StatusCode next() throws IOException {
    available = false;
    statementLength = 0;
    quoted = false;
    while (!exhausted) {
      if (readOffset >= readLength) {
        readLength = input.read(reads);
        readOffset = 0;
        if (readLength < 0) {
          exhausted = true;
          break;
        }
      }
      char character = reads[readOffset++];
      if (character == '\'') quoted = !quoted;
      if (character == ';' && !quoted) {
        if (containsSql()) {
          available = true;
          return StatusCode.OK;
        }
        statementLength = 0;
      } else if (statementLength >= statement.length) {
        return StatusCode.RESOURCE_EXHAUSTED;
      } else {
        statement[statementLength++] = character;
      }
    }
    available = containsSql();
    return StatusCode.OK;
  }

  boolean isAvailable() {
    return available;
  }

  char[] statement() {
    return statement;
  }

  int statementLength() {
    return statementLength;
  }

  private boolean containsSql() {
    for (int index = 0; index < statementLength; index++) {
      if (!Character.isWhitespace(statement[index])) return true;
    }
    return false;
  }
}
