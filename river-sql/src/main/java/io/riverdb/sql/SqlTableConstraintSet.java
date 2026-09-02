package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Bounded retained names and ordered parts for CREATE TABLE constraints. */
final class SqlTableConstraintSet {
  static final int PRIMARY = 1;
  static final int UNIQUE = 2;
  static final int FOREIGN = 3;
  static final int CHECK = 4;
  private static final int MAXIMUM_CONSTRAINTS = 1
      + SqlShapeLimits.MAX_SECONDARY_INDEXES
      + SqlShapeLimits.MAX_FOREIGN_KEYS
      + SqlShapeLimits.MAX_CHECK_CONSTRAINTS;
  private static final int MAXIMUM_PARTS = SqlShapeLimits.MAX_TABLE_KEY_PARTS
      + SqlShapeLimits.MAX_CHECK_CONSTRAINTS * SqlShapeLimits.MAX_TABLE_COLUMNS;

  private final SqlTableConstraintAllocator allocator;
  private byte[] kinds;
  private int[] starts;
  private int[] counts;
  private SqlIdentifier[] names;
  private SqlIdentifier[] tables;
  private SqlIdentifier[] parts;
  private SqlIdentifier[] targets;
  private int count;
  private int partCount;

  SqlTableConstraintSet() { this(SqlTableConstraintAllocator.STANDARD); }

  SqlTableConstraintSet(SqlTableConstraintAllocator retainedArrays) {
    allocator = retainedArrays;
    kinds = allocator.bytes(4);
    starts = allocator.integers(4);
    counts = allocator.integers(4);
    names = allocator.identifiers(4);
    tables = allocator.identifiers(4);
    parts = allocator.identifiers(8);
    targets = allocator.identifiers(8);
  }

  long checkpoint() { return (long) count << 32 | partCount; }

  void rollback(long checkpoint) {
    int retainedCount = (int) (checkpoint >>> 32);
    int retainedParts = (int) checkpoint;
    SqlTableConstraintArrays.clear(retainedCount, count, names, tables);
    SqlTableConstraintArrays.clear(retainedParts, partCount, parts, targets);
    count = retainedCount;
    partCount = retainedParts;
  }

  void reset() { rollback(0); }

  StatusCode begin(int kind) {
    int limit = SqlTableConstraintArrays.limit(kind);
    if (limit < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (ofKind(kind) >= limit) {
      return kind == PRIMARY ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!ensureDefinitions(count + 1)) return StatusCode.RESOURCE_EXHAUSTED;
    kinds[count] = (byte) kind;
    starts[count] = partCount;
    counts[count++] = 0;
    return StatusCode.OK;
  }

  StatusCode addPart(CharSequence part, CharSequence target) {
    if (count == 0 || part == null || part.length() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (duplicate(parts, part)) {
      return kinds[count - 1] == CHECK ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (target != null && duplicate(targets, target)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!ensureParts(partCount + 1)) return StatusCode.RESOURCE_EXHAUSTED;
    parts[partCount].copyFrom(part);
    if (target != null) targets[partCount].copyFrom(target);
    counts[count - 1]++;
    partCount++;
    return StatusCode.OK;
  }

  SqlIdentifier name() { return names[count - 1]; }
  SqlIdentifier table() { return tables[count - 1]; }
  int count() { return count; }
  int kind(int index) { return valid(index) ? kinds[index] : 0; }
  int partCount(int index) { return valid(index) ? counts[index] : 0; }
  SqlIdentifier name(int index) { return valid(index) ? names[index] : null; }
  SqlIdentifier table(int index) { return valid(index) ? tables[index] : null; }
  SqlIdentifier part(int index, int part) {
    return SqlTableConstraintArrays.value(parts, starts, counts, count, index, part);
  }
  SqlIdentifier target(int index, int part) {
    return SqlTableConstraintArrays.value(targets, starts, counts, count, index, part);
  }

  private boolean duplicate(SqlIdentifier[] values, CharSequence value) {
    int start = starts[count - 1];
    for (int index = start; index < partCount; index++) {
      if (SqlTableConstraintArrays.same(values[index], value)) return true;
    }
    return false;
  }

  private boolean ensureDefinitions(int required) {
    if (required <= kinds.length) return true;
    int capacity = Math.min(MAXIMUM_CONSTRAINTS, Math.max(required, kinds.length * 2));
    try {
      byte[] nextKinds = allocator.bytes(capacity);
      int[] nextStarts = allocator.integers(capacity);
      int[] nextCounts = allocator.integers(capacity);
      SqlIdentifier[] nextNames = allocator.identifiers(capacity);
      SqlIdentifier[] nextTables = allocator.identifiers(capacity);
      System.arraycopy(kinds, 0, nextKinds, 0, count);
      System.arraycopy(starts, 0, nextStarts, 0, count);
      System.arraycopy(counts, 0, nextCounts, 0, count);
      SqlTableConstraintArrays.copy(names, nextNames, count);
      SqlTableConstraintArrays.copy(tables, nextTables, count);
      kinds = nextKinds;
      starts = nextStarts;
      counts = nextCounts;
      names = nextNames;
      tables = nextTables;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private boolean ensureParts(int required) {
    if (required <= parts.length) return true;
    if (required > MAXIMUM_PARTS) return false;
    int capacity = Math.min(
        MAXIMUM_PARTS, Math.max(required, parts.length * 2));
    try {
      SqlIdentifier[] nextParts = allocator.identifiers(capacity);
      SqlIdentifier[] nextTargets = allocator.identifiers(capacity);
      SqlTableConstraintArrays.copy(parts, nextParts, partCount);
      SqlTableConstraintArrays.copy(targets, nextTargets, partCount);
      parts = nextParts;
      targets = nextTargets;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private int ofKind(int kind) {
    int matches = 0;
    for (int index = 0; index < count; index++) if (kinds[index] == kind) matches++;
    return matches;
  }

  private boolean valid(int index) { return index >= 0 && index < count; }

}
