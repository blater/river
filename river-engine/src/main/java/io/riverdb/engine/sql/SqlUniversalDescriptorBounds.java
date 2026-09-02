package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorIndexBounds;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Retained tuple-bound workspace for a universal descriptor index scan. */
final class SqlUniversalDescriptorBounds {
  private final SqlDescriptorPrimaryValues lower = new SqlDescriptorPrimaryValues();
  private final SqlDescriptorPrimaryValues upper = new SqlDescriptorPrimaryValues();
  private final RelationalDescriptorIndexBounds bounds =
      new RelationalDescriptorIndexBounds();
  private int lowParts;
  private int highParts;
  private boolean lowerRange;
  private boolean upperRange;

  StatusCode bind(
      TableDescriptor table, SqlCommand command,
      SqlUniversalDescriptorIndexChoice choice, SqlUniversalJoinRows rows,
      int direction) {
    int bytes = table.encodedMaximumRowBytes();
    StatusCode status = lower.begin(table.columnCount(), bytes, command);
    if (status.isOk()) status = upper.begin(table.columnCount(), bytes, command);
    if (status.isOk()) status = equality(choice, rows);
    if (status.isOk()) status = range(choice, rows);
    return status.isOk() ? bounds.set(
        choice.key, lowParts == 0 ? null : lower.buffer(), lowParts,
        !lowerRange || choice.lowerComparison == SqlComparison.GREATER_OR_EQUAL,
        highParts == 0 ? null : upper.buffer(), highParts,
        !upperRange || choice.upperComparison == SqlComparison.LESS_OR_EQUAL,
        direction) : status;
  }

  private StatusCode equality(
      SqlUniversalDescriptorIndexChoice choice, SqlUniversalJoinRows rows) {
    for (int part = 0; part < choice.equalParts; part++) {
      int column = choice.key.columnOrdinalAt(part);
      int descriptor = choice.key.typeDescriptorAt(part);
      StatusCode status = choice.equal[part].assign(lower, column, descriptor, rows);
      if (status.isOk()) status = choice.equal[part].assign(
          upper, column, descriptor, rows);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode range(
      SqlUniversalDescriptorIndexChoice choice, SqlUniversalJoinRows rows) {
    lowParts = choice.equalParts;
    highParts = choice.equalParts;
    lowerRange = false;
    upperRange = false;
    StatusCode status = lower(choice, rows);
    return status.isOk() ? upper(choice, rows) : status;
  }

  private StatusCode lower(
      SqlUniversalDescriptorIndexChoice choice, SqlUniversalJoinRows rows) {
    if (choice.lowerComparison == null) return StatusCode.OK;
    StatusCode status = assign(choice.lower, lower, choice, rows);
    if (status.isOk()) {
      lowParts++;
      lowerRange = true;
    }
    return status == StatusCode.CONFLICT && !choice.lower.nullValue(rows)
        ? StatusCode.OK : status;
  }

  private StatusCode upper(
      SqlUniversalDescriptorIndexChoice choice, SqlUniversalJoinRows rows) {
    if (choice.upperComparison == null) return StatusCode.OK;
    StatusCode status = assign(choice.upper, upper, choice, rows);
    if (status.isOk()) {
      highParts++;
      upperRange = true;
    }
    return status == StatusCode.CONFLICT && !choice.upper.nullValue(rows)
        ? StatusCode.OK : status;
  }

  private static StatusCode assign(
      SqlUniversalDescriptorIndexBinding binding, SqlDescriptorPrimaryValues target,
      SqlUniversalDescriptorIndexChoice choice, SqlUniversalJoinRows rows) {
    int part = choice.equalParts;
    return binding.assign(
        target, choice.key.columnOrdinalAt(part), choice.key.typeDescriptorAt(part), rows);
  }

  RelationalDescriptorIndexBounds bounds() { return bounds; }

  void reset() {
    lower.reset();
    upper.reset();
    lowParts = 0;
    highParts = 0;
    lowerRange = false;
    upperRange = false;
  }
}
