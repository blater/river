package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Validates per-column check metadata and its bounded expression programs. */
final class CatalogTableCheckValidator {
  private CatalogTableCheckValidator() {
  }

  static boolean valid(
      ByteBuffer source,
      int sourceBytes,
      int columns,
      long checkMask,
      int[] checkStack) {
    int total = source.getInt(CatalogRecord.TABLE_CHECK_NODE_TOTAL_OFFSET);
    int programOffset = sourceBytes - total * 13;
    int nodes = 0;
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int comparison = source.getInt(
          CatalogRecord.TABLE_CHECKS_OFFSET + index * Integer.BYTES);
      long value = source.getLong(
          CatalogRecord.TABLE_CHECK_VALUES_OFFSET + index * Long.BYTES);
      int valueDescriptor = source.getInt(
          CatalogRecord.TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET + index * Integer.BYTES);
      int count = Byte.toUnsignedInt(
          source.get(CatalogRecord.TABLE_CHECK_NODE_COUNTS_OFFSET + index));
      boolean checked = index < columns && (checkMask & 1L << index) != 0;
      if (checked
          ? count <= 0
              || nodes > total - count
              || !TableSchema.validFixedValue(valueDescriptor, value)
              || !TableSchema.validCheckComparison(comparison)
              || !validBooleanComparison(valueDescriptor, comparison)
              || !validProgram(
                  source,
                  programOffset + nodes * 13,
                  count,
                  index,
                  valueDescriptor,
                  checkStack)
          : comparison != 0 || value != 0 || valueDescriptor != 0 || count != 0) {
        return false;
      }
      nodes += count;
    }
    return nodes == total;
  }

  private static boolean validBooleanComparison(int descriptor, int comparison) {
    return SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_BOOLEAN
        || comparison == TableSchema.CHECK_EQUAL
        || comparison == TableSchema.CHECK_NOT_EQUAL;
  }

  private static boolean validProgram(
      ByteBuffer source,
      int offset,
      int nodes,
      int owner,
      int valueDescriptor,
      int[] stack) {
    int ownerDescriptor = source.getInt(
        CatalogRecord.TABLE_TYPE_DESCRIPTORS_OFFSET + owner * Integer.BYTES);
    return CatalogCheckProgramValidator.valid(
        source, offset, nodes, owner, ownerDescriptor, valueDescriptor, stack);
  }
}
