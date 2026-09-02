package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Validates and transfers one packed column assembly into its immutable descriptor. */
final class PackedColumnDescriptorPublication {
  private PackedColumnDescriptorPublication() { }

  static StatusCode finish(
      PackedColumnDescriptorStorage source,
      ColumnDescriptorSet.Result result,
      StatusDetail detail) {
    return finish(source, null, result, detail);
  }

  static StatusCode finish(
      PackedColumnDescriptorStorage source,
      ColumnConstraintDescriptorSet constraints,
      ColumnDescriptorSet.Result result,
      StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    if (result == null || source.count() <= 0 || source.count() != source.capacity()) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "incomplete packed columns");
    }
    int count = source.count();
    int[] slots;
    try {
      slots = source.allocateSlots(slotCount(count));
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "column lookup unavailable");
    }
    for (int index = 0; index < count; index++) {
      if (!SqlTypeDescriptor.isValid(source.types()[index]) || !insert(source, slots, index)) {
        return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid or duplicate column");
      }
    }
    try {
      ColumnNameTable names = new ColumnNameTable(
          source.offsets(), source.lengths(), source.names(), slots);
      long charge = SchemaByteCharge.object(0, 4)
          + SchemaByteCharge.array(Integer.BYTES, count)
          + SchemaByteCharge.array(1, count) + names.byteCharge()
          + (constraints == null ? 0 : constraints.byteCharge());
      if (!SchemaByteCharge.fits(charge)) {
        return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "column charge exceeds maximum");
      }
      result.set(new ColumnDescriptorSet(
          source.types(), source.nullable(), names, constraints, charge));
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "column descriptor unavailable");
    }
    source.release();
    if (detail != null) detail.set(StatusCode.OK);
    return StatusCode.OK;
  }

  private static boolean insert(PackedColumnDescriptorStorage source, int[] slots, int ordinal) {
    int offset = source.offsets()[ordinal];
    int length = source.lengths()[ordinal];
    long hash = Utf8NameCodec.hash(source.names(), offset, length);
    int slot = (int) hash & slots.length - 1;
    while (slots[slot] != 0) {
      int existing = slots[slot] - 1;
      if (source.lengths()[existing] == length
          && same(source.names(), source.offsets()[existing], offset, length)) return false;
      slot = slot + 1 & slots.length - 1;
    }
    slots[slot] = ordinal + 1;
    return true;
  }

  private static boolean same(byte[] bytes, int left, int right, int length) {
    for (int index = 0; index < length; index++) {
      if (bytes[left + index] != bytes[right + index]) return false;
    }
    return true;
  }

  private static int slotCount(int count) {
    int slots = 2;
    while (slots < count * 2) slots <<= 1;
    return slots;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, String message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }
}
