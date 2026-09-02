package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorIndexBounds;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Builds the typed tuple bounds selected for one descriptor scan. */
final class SqlDescriptorIndexBoundsPreparation {
  private final SqlDescriptorPrimaryValues lower = new SqlDescriptorPrimaryValues();
  private final SqlDescriptorPrimaryValues upper = new SqlDescriptorPrimaryValues();
  private final RelationalDescriptorIndexBounds bounds =
      new RelationalDescriptorIndexBounds();
  private final SqlDescriptorIndexRange range = new SqlDescriptorIndexRange();

  StatusCode prepare(
      SqlCommand command, TableDescriptor table,
      SqlDescriptorPredicateBindings bindings, SqlDescriptorIndexChoice choice) {
    int textBytes = table.encodedMaximumRowBytes();
    StatusCode status = lower.begin(table.columnCount(), textBytes, command);
    if (status.isOk()) status = upper.begin(table.columnCount(), textBytes, command);
    if (status.isOk()) status = equality(bindings, choice);
    return status.isOk() ? range(bindings, choice) : status;
  }

  private StatusCode equality(
      SqlDescriptorPredicateBindings bindings, SqlDescriptorIndexChoice choice) {
    for (int part = 0; part < choice.equalityParts; part++) {
      int column = choice.key.columnOrdinalAt(part);
      int leaf = SqlDescriptorIndexSelection.find(bindings, column, SqlComparison.EQUAL);
      StatusCode status = assign(
          bindings, leaf, column, choice.key.typeDescriptorAt(part), lower, false);
      if (status.isOk()) status = assign(
          bindings, leaf, column, choice.key.typeDescriptorAt(part), upper, false);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode range(
      SqlDescriptorPredicateBindings bindings, SqlDescriptorIndexChoice choice) {
    range.begin(choice.equalityParts);
    StatusCode status = range.lower(bindings, choice, lower);
    if (status.isOk()) status = range.upper(bindings, choice, upper);
    return status.isOk() ? bounds.set(
        choice.key, range.lowParts() == 0 ? null : lower.buffer(), range.lowParts(),
        range.lowerInclusive(bindings, choice),
        range.highParts() == 0 ? null : upper.buffer(), range.highParts(),
        range.upperInclusive(bindings, choice), choice.direction) : status;
  }

  static StatusCode assign(
      SqlDescriptorPredicateBindings bindings, int leaf, int column,
      int target, SqlDescriptorPrimaryValues values, boolean upper) {
    StatusCode status = values.assign(column, bindings.descriptor(leaf, upper), target,
        bindings.literalHigh(leaf, upper), bindings.literal(leaf, upper));
    return status == StatusCode.INVALID_EXTERNAL_INPUT ? StatusCode.CONFLICT : status;
  }

  RelationalDescriptorIndexBounds bounds() { return bounds; }

  void reset() {
    lower.reset();
    upper.reset();
  }
}
