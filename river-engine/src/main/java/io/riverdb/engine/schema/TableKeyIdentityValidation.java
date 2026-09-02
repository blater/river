package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Validates durable key identity binding independently of key shape and role. */
final class TableKeyIdentityValidation {
  private TableKeyIdentityValidation() {}

  static StatusCode validate(
      KeyDescriptor primary,
      KeyDescriptor[] secondary,
      KeyDescriptor[] foreign,
      boolean requireBound,
      StatusDetail detail) {
    int secondaryCount = secondary == null ? 0 : secondary.length;
    int foreignCount = foreign == null ? 0 : foreign.length;
    int total = (primary == null ? 0 : 1) + secondaryCount + foreignCount;
    for (int index = 0; index < total; index++) {
      KeyDescriptor key = keyAt(primary, secondary, foreign, index);
      if (requireBound && key.keyId() <= 0) {
        return fail(detail, "table key id is not bound");
      }
      if (key.keyId() > 0) {
        for (int later = index + 1; later < total; later++) {
          if (key.keyId() == keyAt(primary, secondary, foreign, later).keyId()) {
            return fail(detail, "duplicate table key id");
          }
        }
      }
    }
    return StatusCode.OK;
  }

  private static KeyDescriptor keyAt(
      KeyDescriptor primary, KeyDescriptor[] secondary, KeyDescriptor[] foreign, int index) {
    if (primary != null) {
      if (index == 0) return primary;
      index--;
    }
    if (secondary != null && index < secondary.length) return secondary[index];
    return foreign[index - (secondary == null ? 0 : secondary.length)];
  }

  private static StatusCode fail(StatusDetail detail, CharSequence message) {
    if (detail != null) detail.set(StatusCode.INVALID_EXTERNAL_INPUT).append(message);
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
