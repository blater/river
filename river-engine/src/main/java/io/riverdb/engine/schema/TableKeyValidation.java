package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Validates table key-role composition before immutable publication. */
final class TableKeyValidation {
  private TableKeyValidation() {
  }

  static StatusCode validate(
      KeyDescriptor primary,
      KeyDescriptor[] secondary,
      KeyDescriptor[] foreign,
      ColumnDescriptorSet columns,
      int maximumSecondary,
      int maximumForeign,
      StatusDetail detail) {
    if (secondary != null && secondary.length > maximumSecondary
        || foreign != null && foreign.length > maximumForeign) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "table key count exceeds maximum");
    }
    if (primary != null && (primary.kind() != KeyDescriptor.KIND_PRIMARY
        || !belongsTo(primary, columns))) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid primary key kind");
    }
    StatusCode status = validateKinds(
        secondary, columns, KeyDescriptor.KIND_SECONDARY, true, detail);
    if (!status.isOk()) return status;
    status = validateKinds(foreign, columns, KeyDescriptor.KIND_FOREIGN, false, detail);
    return status.isOk()
        ? validateNames(primary, secondary, foreign, detail) : status;
  }

  static long charge(
      KeyDescriptor primary, KeyDescriptor[] secondary, KeyDescriptor[] foreign) {
    long charge = primary == null ? 0 : primary.byteCharge();
    if (secondary != null) {
      for (KeyDescriptor key : secondary) charge += key.byteCharge();
    }
    if (foreign != null) {
      for (KeyDescriptor key : foreign) charge += key.byteCharge();
    }
    return charge;
  }

  private static StatusCode validateKinds(
      KeyDescriptor[] keys,
      ColumnDescriptorSet columns,
      int expected,
      boolean allowUnique,
      StatusDetail detail) {
    if (keys == null) return StatusCode.OK;
    for (KeyDescriptor key : keys) {
      if (key == null || key.kind() != expected
          && !(allowUnique && key.kind() == KeyDescriptor.KIND_UNIQUE)
          || key != null && !belongsTo(key, columns)) {
        return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid table key kind");
      }
    }
    return StatusCode.OK;
  }

  private static boolean belongsTo(KeyDescriptor key, ColumnDescriptorSet columns) {
    if (key.columns() != columns) return false;
    for (int index = 0; index < key.partCount(); index++) {
      int ordinal = key.columnOrdinalAt(index);
      if (ordinal < 0 || ordinal >= columns.count()
          || key.typeDescriptorAt(index) != columns.typeDescriptorAt(ordinal)) return false;
    }
    return true;
  }

  private static StatusCode validateNames(
      KeyDescriptor primary,
      KeyDescriptor[] secondary,
      KeyDescriptor[] foreign,
      StatusDetail detail) {
    int count = (primary == null ? 0 : 1) + secondary.length + foreign.length;
    for (int index = 0; index < count; index++) {
      KeyDescriptor key = at(primary, secondary, foreign, index);
      if (!key.hasName()) continue;
      for (int previous = 0; previous < index; previous++) {
        if (at(primary, secondary, foreign, previous).matchesName(key.name())) {
          return fail(detail, StatusCode.CONFLICT, "duplicate key name");
        }
      }
    }
    return StatusCode.OK;
  }

  private static KeyDescriptor at(
      KeyDescriptor primary, KeyDescriptor[] secondary, KeyDescriptor[] foreign, int index) {
    if (primary != null) {
      if (index == 0) return primary;
      index--;
    }
    return index < secondary.length ? secondary[index] : foreign[index - secondary.length];
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }
}
