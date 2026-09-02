package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import io.riverdb.protocol.ProtocolResponse;
import java.util.Arrays;

/** Reusable primitive arrays and text scratch for decoded client results. */
final class RiverClientResultWorkspace {
  private final RiverClientResultAllocator allocator;
  private long[] values = new long[8];
  private long[] decimalHighs = new long[8];
  private int[] descriptors = new int[8];
  private long[] nullWords = new long[1];
  private final char[] text = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];

  RiverClientResultWorkspace() {
    this(RiverClientResultAllocator.STANDARD);
  }

  RiverClientResultWorkspace(RiverClientResultAllocator resultAllocator) {
    allocator = resultAllocator;
  }

  StatusCode copyCommand(ProtocolResponse response, CommandResult target) {
    int columns = response.columnCount();
    StatusCode status = reserve(columns);
    if (!status.isOk()) return status;
    for (int index = 0; index < columns; index++) {
      values[index] = response.valueAt(index);
      decimalHighs[index] = response.decimalUnscaledHighAt(index);
      descriptors[index] = response.typeDescriptorAt(index);
    }
    copyNulls(response);
    status = target.reserve(columns, response.textBytesUsed());
    if (!status.isOk()) return status;
    status = target.complete(
        response.affectedRows(), response.commitSequence(), response.transactionActive(),
        response.rowAvailable(), response.key(), decimalHighs, values, nullWords,
        response.nullWordCount(), descriptors, columns);
    return status.isOk() ? copyCommandText(response, target, columns) : status;
  }

  StatusCode copyRow(
      ProtocolResponse response, RowResult target,
      int[] expectedDescriptors, int columns) {
    StatusCode status = reserve(columns);
    if (!status.isOk()) return status;
    for (int index = 0; index < columns; index++) {
      values[index] = response.valueAt(index);
      decimalHighs[index] = response.decimalUnscaledHighAt(index);
      descriptors[index] = expectedDescriptors[index];
    }
    copyNulls(response);
    status = target.complete(
        response.key(), decimalHighs, values, nullWords,
        response.nullWordCount(), descriptors, columns);
    return status.isOk() ? copyRowText(response, target, columns) : status;
  }

  private StatusCode copyCommandText(
      ProtocolResponse response, CommandResult target, int columns) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < columns; index++) {
      if (response.isVarchar(index) && !response.isNull(index)) {
        int length = response.copyTextAt(index, text, 0);
        status = length < 0 ? StatusCode.INVALID_EXTERNAL_INPUT
            : target.setTextAt(index, text, 0, length);
      }
    }
    return status;
  }

  private StatusCode copyRowText(
      ProtocolResponse response, RowResult target, int columns) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < columns; index++) {
      if (response.isVarchar(index) && !response.isNull(index)) {
        int length = response.copyTextAt(index, text, 0);
        status = length < 0 ? StatusCode.INVALID_EXTERNAL_INPUT
            : target.setTextAt(index, text, 0, length);
      }
    }
    return status;
  }

  StatusCode reserve(int columns) {
    if (columns < 0 || columns > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (columns <= values.length) return StatusCode.OK;
    int capacity = Math.min(
        SqlShapeLimits.MAX_RESULT_COLUMNS, Math.max(columns, values.length << 1));
    try {
      long[] nextValues = allocator.copy(values, capacity);
      long[] nextHighs = allocator.copy(decimalHighs, capacity);
      int[] nextDescriptors = allocator.copy(descriptors, capacity);
      long[] nextNulls = allocator.copy(nullWords, (capacity + 63) >>> 6);
      values = nextValues;
      decimalHighs = nextHighs;
      descriptors = nextDescriptors;
      nullWords = nextNulls;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int retainedColumns() {
    return Math.min(values.length, Math.min(decimalHighs.length, descriptors.length));
  }

  private void copyNulls(ProtocolResponse response) {
    Arrays.fill(nullWords, 0L);
    for (int word = 0; word < response.nullWordCount(); word++) {
      nullWords[word] = response.nullWord(word);
    }
  }
}
