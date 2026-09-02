package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlComparison;

/** Accumulates the optional lower and upper range parts of descriptor bounds. */
final class SqlDescriptorIndexRange {
  private int lowParts;
  private int highParts;
  private boolean lowerRange;
  private boolean upperRange;

  void begin(int equalityParts) {
    lowParts = equalityParts;
    highParts = equalityParts;
    lowerRange = false;
    upperRange = false;
  }

  StatusCode lower(
      SqlDescriptorPredicateBindings bindings, SqlDescriptorIndexChoice choice,
      SqlDescriptorPrimaryValues values) {
    if (choice.lowerLeaf < 0) return StatusCode.OK;
    int column = choice.key.columnOrdinalAt(choice.equalityParts);
    StatusCode status = SqlDescriptorIndexBoundsPreparation.assign(
        bindings, choice.lowerLeaf, column,
        choice.key.typeDescriptorAt(choice.equalityParts), values, false);
    if (status.isOk()) {
      lowParts++;
      lowerRange = true;
    }
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  StatusCode upper(
      SqlDescriptorPredicateBindings bindings, SqlDescriptorIndexChoice choice,
      SqlDescriptorPrimaryValues values) {
    if (choice.upperLeaf < 0) return StatusCode.OK;
    int column = choice.key.columnOrdinalAt(choice.equalityParts);
    StatusCode status = SqlDescriptorIndexBoundsPreparation.assign(
        bindings, choice.upperLeaf, column,
        choice.key.typeDescriptorAt(choice.equalityParts), values, true);
    if (status.isOk()) {
      highParts++;
      upperRange = true;
    }
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  int lowParts() { return lowParts; }
  int highParts() { return highParts; }
  boolean lowerInclusive(
      SqlDescriptorPredicateBindings bindings, SqlDescriptorIndexChoice choice) {
    return !lowerRange || choice.lowerLeaf < 0 || bindings.between(choice.lowerLeaf)
        || bindings.comparison(choice.lowerLeaf) == SqlComparison.GREATER_OR_EQUAL;
  }
  boolean upperInclusive(
      SqlDescriptorPredicateBindings bindings, SqlDescriptorIndexChoice choice) {
    return !upperRange || choice.upperLeaf < 0 || bindings.between(choice.upperLeaf)
        || bindings.comparison(choice.upperLeaf) == SqlComparison.LESS_OR_EQUAL;
  }
}
