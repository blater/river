package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Publishes finalized aggregate and group-key values into an owned block row. */
final class SqlBlockAggregatePublisher {
  private final BoundSqlStatement bound;
  private final byte[] groupText = new byte[Utf8Text.MAXIMUM_BYTES];
  private ByteBuffer aggregateText;

  SqlBlockAggregatePublisher(BoundSqlStatement statement) {
    bound = statement;
  }

  StatusCode publish(
      int block,
      SqlAggregateAccumulatorSet accumulator,
      SqlBlockRow groupKey,
      SqlBlockRow destination,
      boolean grouped) {
    int selected = bound.command.aggregateOutputInvocation(0);
    destination.reset(grouped ? 2 : 1);
    if (grouped) publishGroup(block, groupKey, destination);
    int output = grouped ? 1 : 0;
    if (accumulator.nullValue(selected)) {
      destination.setNull(output);
      return StatusCode.OK;
    }
    destination.setValue(output, accumulator.value(selected));
    if (SqlTypeDescriptor.typeId(bound.aggregates.resultDescriptor(selected))
        != SqlTypeDescriptor.TYPE_ID_VARCHAR) return StatusCode.OK;
    int length = accumulator.textLength(selected);
    if (length == 0) {
      destination.setText(output, destination.text(output), 0, 0);
      return StatusCode.OK;
    }
    if (aggregateText == null) aggregateText = ByteBuffer.wrap(accumulator.text());
    int characters = Utf8Text.decode(
        aggregateText,
        accumulator.textOffset(selected),
        length,
        destination.text(output),
        0);
    if (characters < 0) return StatusCode.CORRUPTION;
    destination.setText(output, destination.text(output), 0, characters);
    return StatusCode.OK;
  }

  int encodeGroupKey(SqlBlockSchema operands, SqlBlockRow groupKey) {
    eraseGroupText();
    if (groupKey.nullValue(0) || !operands.varchar(0)) return 0;
    return Utf8Text.encode(
        groupKey.text(0),
        0,
        groupKey.textLength(0),
        Utf8Text.MAXIMUM_SCALARS,
        groupText,
        0);
  }

  byte[] groupText() { return groupText; }

  void reset() {
    eraseGroupText();
  }

  private void publishGroup(
      int block, SqlBlockRow groupKey, SqlBlockRow destination) {
    if (groupKey.nullValue(0)) {
      destination.setNull(0);
      return;
    }
    destination.setValue(0, groupKey.value(0));
    if (bound.blockPlans().schema(block).varchar(0)) {
      destination.setText(0, groupKey.text(0), 0, groupKey.textLength(0));
    }
  }

  private void eraseGroupText() {
    for (int index = 0; index < groupText.length; index++) groupText[index] = 0;
  }
}
