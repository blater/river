package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.sql.SqlComparison;

/** Reusable winning composite descriptor-index shape and its bound suppliers. */
final class SqlUniversalDescriptorIndexChoice {
  final SqlUniversalDescriptorIndexBinding[] equal =
      new SqlUniversalDescriptorIndexBinding[KeyDescriptor.MAXIMUM_PARTS];
  final SqlUniversalDescriptorIndexBinding lower =
      new SqlUniversalDescriptorIndexBinding();
  final SqlUniversalDescriptorIndexBinding upper =
      new SqlUniversalDescriptorIndexBinding();
  KeyDescriptor key;
  SqlComparison lowerComparison;
  SqlComparison upperComparison;
  int equalParts;
  int score = -1;

  SqlUniversalDescriptorIndexChoice() {
    for (int part = 0; part < equal.length; part++) {
      equal[part] = new SqlUniversalDescriptorIndexBinding();
    }
  }

  void select(KeyDescriptor selected, int equality, boolean low, boolean high) {
    key = selected;
    equalParts = equality;
    score = equality * 10 + (low || high ? 1 : 0);
  }

  void reset() {
    for (SqlUniversalDescriptorIndexBinding binding : equal) binding.reset();
    lower.reset();
    upper.reset();
    key = null;
    lowerComparison = null;
    upperComparison = null;
    equalParts = 0;
    score = -1;
  }

  boolean exact() { return key != null && equalParts == key.partCount(); }
  boolean unique() { return key != null && key.isUnique(); }
}
