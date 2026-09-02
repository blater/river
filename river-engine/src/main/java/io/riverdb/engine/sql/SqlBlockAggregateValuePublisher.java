package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Decodes one finalized aggregate value into an owned block row. */
final class SqlBlockAggregateValuePublisher {
  private ByteBuffer aggregateText;

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
    } else {
      destination.setValue(output, accumulator.value(selected));
    }
    if (SqlTypeDescriptor.typeId(descriptor)
        != SqlTypeDescriptor.TYPE_ID_VARCHAR) return StatusCode.OK;
    int length = accumulator.textLength(selected);
    if (length == 0) {
      destination.setTextLength(output, 0);
      return StatusCode.OK;
    }
    if (aggregateText == null) aggregateText = ByteBuffer.wrap(accumulator.text());
    StatusCode status = destination.prepareText(output);
    if (!status.isOk()) return status;
    int characters = Utf8Text.decode(
        aggregateText,
        accumulator.textOffset(selected),
        length,
        destination.text(output),
        0);
    if (characters < 0) return StatusCode.CORRUPTION;
    destination.setTextLength(output, characters);
    return StatusCode.OK;
  }
}
