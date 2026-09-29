package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Copies one finalized aggregate value into an owned block row. */
final class SqlBlockAggregateValuePublisher {
  StatusCode publish(
      SqlAggregateAccumulatorSet accumulator,
      int selected,
      int descriptor,
      SqlBlockRow destination,
      int output) {
    if (accumulator.nullValue(selected)) {
      destination.setNull(output);
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      destination.setDecimal128(
          output, accumulator.highValue(selected), accumulator.value(selected));
    } else destination.setValue(output, accumulator.value(selected));
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.OK;
    }
    return destination.setUtf8(
        output, accumulator.text(),
        accumulator.textOffset(selected), accumulator.textLength(selected));
  }
}
