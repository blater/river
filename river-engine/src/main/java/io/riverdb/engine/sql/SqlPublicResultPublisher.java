package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;

/** Reusable primitive transfer scratch between internal and public result owners. */
public final class SqlPublicResultPublisher {
  private long[] decimalHighs = new long[0];
  private long[] values = new long[0];
  private int[] descriptors = new int[0];
  private long[] nullWords = new long[0];
  private final char[] text = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];

  public StatusCode reserve(int columns) {
    if (columns < 0 || columns > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int words = (columns + Long.SIZE - 1) >>> 6;
    if (columns <= values.length && words <= nullWords.length) return StatusCode.OK;
    int laneCapacity = BoundedArrayGrowth.capacity(
        values.length, columns, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    int maximumWords = (SqlShapeLimits.MAX_RESULT_COLUMNS + Long.SIZE - 1) >>> 6;
    int wordCapacity = BoundedArrayGrowth.capacity(
        nullWords.length, words, maximumWords, 1);
    try {
      long[] nextValues = new long[laneCapacity];
      long[] nextDecimalHighs = new long[laneCapacity];
      int[] nextDescriptors = new int[laneCapacity];
      long[] nextNullWords = new long[wordCapacity];
      values = nextValues;
      decimalHighs = nextDecimalHighs;
      descriptors = nextDescriptors;
      nullWords = nextNullWords;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public StatusCode publish(SqlExecutionResult source, CommandResult result) {
    int columns = source.columnCount();
    StatusCode status = prepare(source, columns, result);
    if (!status.isOk()) return status;
    status = result.complete(
        source.affectedRows(), source.commitSequence(), source.transactionActive(),
        source.hasValue(), source.key(), decimalHighs, values, nullWords,
        source.nullWordCount(), descriptors, columns);
    return status.isOk() ? copyText(source, result, columns) : status;
  }

  public StatusCode publish(SqlScanRowResult source, RowResult result) {
    int columns = source.columnCount();
    StatusCode status = prepare(source, columns, result);
    if (!status.isOk()) return status;
    status = result.complete(
        source.key(), decimalHighs, values, nullWords,
        source.nullWordCount(), descriptors, columns);
    return status.isOk() ? copyText(source, result, columns) : status;
  }

  private StatusCode prepare(
      SqlExecutionResult source, int columns, CommandResult result) {
    StatusCode status = reserve(columns);
    int textBytes = 0;
    for (int index = 0; status.isOk() && index < columns; index++) {
      values[index] = source.valueAt(index);
      decimalHighs[index] = source.highValueAt(index);
      descriptors[index] = source.typeDescriptorAt(index);
      int length = source.textLengthAt(index);
      if (length > 0) textBytes += length * 4;
    }
    if (status.isOk()) copyNulls(source, source.nullWordCount());
    return status.isOk() ? result.reserve(columns, textBytes) : status;
  }

  private StatusCode prepare(
      SqlScanRowResult source, int columns, RowResult result) {
    int words = (columns + Long.SIZE - 1) >>> 6;
    StatusCode status = columns <= values.length && words <= nullWords.length
        ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
    for (int index = 0; status.isOk() && index < columns; index++) {
      values[index] = source.valueAt(index);
      decimalHighs[index] = source.highValueAt(index);
      descriptors[index] = source.typeDescriptorAt(index);
    }
    if (status.isOk()) copyNulls(source, source.nullWordCount());
    return status;
  }

  private void copyNulls(SqlExecutionResult source, int words) {
    for (int word = 0; word < words; word++) nullWords[word] = source.nullWord(word);
  }

  private void copyNulls(SqlScanRowResult source, int words) {
    for (int word = 0; word < words; word++) nullWords[word] = source.nullWord(word);
  }

  private StatusCode copyText(
      SqlExecutionResult source, CommandResult result, int columns) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < columns; index++) {
      if (!source.isVarchar(index) || source.isNull(index)) continue;
      int length = source.copyTextAt(index, text, 0);
      status = length < 0
          ? StatusCode.INVARIANT_BROKEN : result.setTextAt(index, text, 0, length);
    }
    return status;
  }

  private StatusCode copyText(SqlScanRowResult source, RowResult result, int columns) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < columns; index++) {
      if (!source.isVarchar(index) || source.isNull(index)) continue;
      int length = source.copyTextAt(index, text, 0);
      status = length < 0
          ? StatusCode.INVARIANT_BROKEN : result.setTextAt(index, text, 0, length);
    }
    return status;
  }
}
