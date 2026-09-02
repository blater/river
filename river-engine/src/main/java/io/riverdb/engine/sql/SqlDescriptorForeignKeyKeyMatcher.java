package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Matches FK target columns to an exact primary or unique key. */
final class SqlDescriptorForeignKeyKeyMatcher {
  private long keyId;

  long keyId() { return keyId; }

  StatusCode match(
      TableDescriptor source, TableDescriptor target, int[] localParts,
      int[] targetParts, int count, boolean self) {
    for (int part = 0; part < count; part++) {
      if (targetParts[part] < 0
          || source.typeDescriptorAt(localParts[part])
              != target.typeDescriptorAt(targetParts[part])) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    KeyDescriptor match = matching(target.primaryKey(), targetParts, count)
        ? target.primaryKey() : null;
    for (int index = 0; match == null && index < target.secondaryKeyCount(); index++) {
      KeyDescriptor candidate = target.secondaryKeyAt(index);
      if (candidate.isUnique() && matching(candidate, targetParts, count)) match = candidate;
    }
    if (match == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int ordinal = self ? physicalOrdinal(target, match) : 0;
    if (self && ordinal < 0) return StatusCode.CORRUPTION;
    keyId = self ? -ordinal - 1L : match.keyId();
    return StatusCode.OK;
  }

  private static boolean matching(KeyDescriptor candidate, int[] parts, int count) {
    if (candidate == null || candidate.partCount() != count) return false;
    for (int part = 0; part < count; part++) {
      if (candidate.columnOrdinalAt(part) != parts[part]) return false;
    }
    return true;
  }

  private static int physicalOrdinal(TableDescriptor table, KeyDescriptor key) {
    if (table.primaryKey() == key) return 0;
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      if (table.secondaryKeyAt(index) == key) {
        return (table.primaryKey() == null ? 0 : 1) + index;
      }
    }
    return -1;
  }
}
