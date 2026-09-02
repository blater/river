package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.PackedColumnDescriptorBuilder;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import java.nio.ByteBuffer;

/** Retains one packed column set while catalog chunks stream into an immutable descriptor. */
final class CatalogColumnAssembly {
  private final PackedColumnDescriptorBuilder builder = new PackedColumnDescriptorBuilder();
  private final ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
  private final CatalogColumnConstraintAssembly constraints =
      new CatalogColumnConstraintAssembly();
  private ColumnDescriptorSet columns;
  private int expected;
  private int accepted;

  StatusCode begin(int columnCount, int maximumNameBytes) {
    reset();
    StatusCode status = builder.begin(columnCount, maximumNameBytes);
    if (status.isOk()) status = constraints.begin(columnCount);
    if (status.isOk()) expected = columnCount;
    return status;
  }

  StatusCode accept(CatalogDefinitionRecord record, ByteBuffer payload, int payloadStart) {
    if (columns != null || record.logicalStart() != accepted
        || accepted > expected - record.logicalCount()) return StatusCode.CORRUPTION;
    StatusCode status;
    try {
      status = CatalogColumnPayloadCodec.decodeInto(
          payload, payloadStart, record.payloadBytes(), record.logicalCount(), accepted,
          builder, constraints);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) accepted += record.logicalCount();
    return status;
  }

  StatusCode freeze() {
    if (columns != null) return StatusCode.OK;
    if (!complete()) return StatusCode.CORRUPTION;
    StatusCode status;
    try {
      status = constraints.freeze(expected);
      if (status.isOk()) status = builder.finish(constraints.value(), result, null);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!status.isOk()) {
      return status == StatusCode.RESOURCE_EXHAUSTED ? status : StatusCode.CORRUPTION;
    }
    columns = result.value();
    return StatusCode.OK;
  }

  boolean complete() { return expected > 0 && accepted == expected; }
  ColumnDescriptorSet value() { return columns; }

  void reset() {
    builder.reset();
    constraints.reset();
    result.reset();
    columns = null;
    expected = 0;
    accepted = 0;
  }
}
