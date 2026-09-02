package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Validates key column membership and captures its type descriptors. */
final class KeyDescriptorPartValidation {
  private KeyDescriptorPartValidation() {
  }

  static StatusCode validate(
      int kind, ColumnDescriptorSet columns, int[] ordinals, int[] descriptors,
      StatusDetail detail) {
    for (int index = 0; index < ordinals.length; index++) {
      int ordinal = ordinals[index];
      if (ordinal < 0 || ordinal >= columns.count()) {
        return fail(detail, "key column is out of range");
      }
      for (int previous = 0; previous < index; previous++) {
        if (ordinals[previous] == ordinal) return fail(detail, "duplicate key column");
      }
      if (kind == KeyDescriptor.KIND_PRIMARY && columns.isNullable(ordinal)) {
        return fail(detail, "primary key column is nullable");
      }
      descriptors[index] = columns.typeDescriptorAt(ordinal);
    }
    return StatusCode.OK;
  }

  private static StatusCode fail(StatusDetail detail, CharSequence message) {
    return KeyDescriptorStatus.fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, message);
  }
}
