package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.lock.LockDeadline;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/** Raw property values with defaults and duplicate tracking. */
final class RuntimeConfigProperties {
  static final String CACHE = "river.sql.materialized.cache";
  static final String SCHEMA_CACHE = "river.sql.schema-cache";
  static final String SESSION_SHAPE_CACHE = "river.sql.session-shape-cache";
  static final String PAGE = "river.sql.materialized.page";
  static final String SORT_RUN = "river.sql.materialized.sort-run";
  static final String HASH_BUILD_ROWS = "river.sql.join.hash-build-rows";
  static final String HASH_BUCKETS = "river.sql.join.hash-buckets";
  static final String SPILL_DIRECTORY = "river.sql.materialized.spill-directory";
  static final String LOCK_WAIT_TIMEOUT = "river.tx.lock-wait-timeout";

  private static final int CACHE_BIT = 1;
  private static final int PAGE_BIT = 1 << 1;
  private static final int SORT_RUN_BIT = 1 << 2;
  private static final int HASH_BUILD_ROWS_BIT = 1 << 3;
  private static final int HASH_BUCKETS_BIT = 1 << 4;
  private static final int SPILL_DIRECTORY_BIT = 1 << 5;
  private static final int SCHEMA_CACHE_BIT = 1 << 6;
  private static final int SESSION_SHAPE_CACHE_BIT = 1 << 7;
  private static final int LOCK_WAIT_TIMEOUT_BIT = 1 << 8;

  private String cache = "auto";
  private String schemaCache = "auto";
  private String sessionShapeCache = "auto";
  private String page = "64KB";
  private String sortRun = "auto";
  private String hashBuildRows = "1024";
  private String hashBuckets = "2048";
  private String spillDirectory;
  private String lockWaitTimeout = LockDeadline.DEFAULT_WAIT_NANOS + "ns";
  private int seen;

  StatusCode parse(String decoded, StatusDetail detail) {
    try (BufferedReader reader = new BufferedReader(new StringReader(decoded))) {
      int lineNumber = 0;
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        StatusCode status = parseLine(line, lineNumber, detail);
        if (!status.isOk()) return status;
      }
      return StatusCode.OK;
    } catch (IOException impossible) {
      detail.set(StatusCode.INVARIANT_BROKEN)
          .append("in-memory configuration read failed");
      return StatusCode.INVARIANT_BROKEN;
    }
  }

  String cache() { return cache; }
  String schemaCache() { return schemaCache; }
  String sessionShapeCache() { return sessionShapeCache; }
  String page() { return page; }
  String sortRun() { return sortRun; }
  String hashBuildRows() { return hashBuildRows; }
  String hashBuckets() { return hashBuckets; }
  String spillDirectory() { return spillDirectory; }
  String lockWaitTimeout() { return lockWaitTimeout; }

  private StatusCode parseLine(
      String line,
      int lineNumber,
      StatusDetail detail) {
    int start = RuntimeConfigText.skipSpace(line, 0, line.length());
    if (start == line.length() || line.charAt(start) == '#') return StatusCode.OK;
    int equals = line.indexOf('=', start);
    if (equals < 0) return malformed(lineNumber, detail);
    int keyEnd = RuntimeConfigText.trimEnd(line, start, equals);
    int valueStart = RuntimeConfigText.skipSpace(line, equals + 1, line.length());
    int valueEnd = RuntimeConfigText.trimEnd(line, valueStart, line.length());
    if (keyEnd == start || valueStart == valueEnd) return malformed(lineNumber, detail);
    return set(line.substring(start, keyEnd), line.substring(valueStart, valueEnd), detail);
  }

  private StatusCode set(String key, String value, StatusDetail detail) {
    int bit = propertyBit(key);
    if (bit == 0) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("unknown property: ")
          .append(key);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if ((seen & bit) != 0) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("duplicate property: ")
          .append(key);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    seen |= bit;
    assign(bit, value);
    return StatusCode.OK;
  }

  private static int propertyBit(String key) {
    return switch (key) {
      case CACHE -> CACHE_BIT;
      case SCHEMA_CACHE -> SCHEMA_CACHE_BIT;
      case SESSION_SHAPE_CACHE -> SESSION_SHAPE_CACHE_BIT;
      case PAGE -> PAGE_BIT;
      case SORT_RUN -> SORT_RUN_BIT;
      case HASH_BUILD_ROWS -> HASH_BUILD_ROWS_BIT;
      case HASH_BUCKETS -> HASH_BUCKETS_BIT;
      case SPILL_DIRECTORY -> SPILL_DIRECTORY_BIT;
      case LOCK_WAIT_TIMEOUT -> LOCK_WAIT_TIMEOUT_BIT;
      default -> 0;
    };
  }

  private void assign(int bit, String value) {
    switch (bit) {
      case CACHE_BIT -> cache = value;
      case SCHEMA_CACHE_BIT -> schemaCache = value;
      case SESSION_SHAPE_CACHE_BIT -> sessionShapeCache = value;
      case PAGE_BIT -> page = value;
      case SORT_RUN_BIT -> sortRun = value;
      case HASH_BUILD_ROWS_BIT -> hashBuildRows = value;
      case HASH_BUCKETS_BIT -> hashBuckets = value;
      case SPILL_DIRECTORY_BIT -> spillDirectory = value;
      case LOCK_WAIT_TIMEOUT_BIT -> lockWaitTimeout = value;
      default -> throw new IllegalStateException();
    }
  }

  private static StatusCode malformed(int lineNumber, StatusDetail detail) {
    detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
        .append("malformed river.properties line ")
        .append(lineNumber);
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
