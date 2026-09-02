package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.text.Utf8Text;

/** Validates key metadata independently of the column parts. */
final class KeyDescriptorSemanticValidation {
  private KeyDescriptorSemanticValidation() {
  }

  static StatusCode validate(
      long keyId, int kind, boolean unique, ColumnDescriptorSet columns, int[] ordinals,
      long referencedKeyId, CharSequence name, KeyDescriptor.Result result, StatusDetail detail,
      boolean requirePositiveId) {
    if (result == null || columns == null || ordinals == null || keyId < 0
        || requirePositiveId && keyId == 0) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid key inputs");
    }
    if (kind < KeyDescriptor.KIND_PRIMARY || kind > KeyDescriptor.KIND_SECONDARY
        || ordinals.length < 1) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid key kind or parts");
    }
    if (ordinals.length > KeyDescriptor.MAXIMUM_PARTS) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "key parts exceed allowed count");
    }
    if (!validSemantics(kind, unique, referencedKeyId, !requirePositiveId && keyId == 0)) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid key semantics");
    }
    if (name != null && (name.length() < 1
        || name.length() > KeyDescriptor.MAXIMUM_NAME_LENGTH
        || Utf8Text.encodedLength(name, KeyDescriptor.MAXIMUM_NAME_LENGTH) < 1)) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid key name");
    }
    return StatusCode.OK;
  }

  private static boolean validSemantics(
      int kind, boolean unique, long referencedKeyId, boolean allowUnboundReference) {
    if ((kind == KeyDescriptor.KIND_PRIMARY || kind == KeyDescriptor.KIND_UNIQUE) && !unique) {
      return false;
    }
    if (kind == KeyDescriptor.KIND_FOREIGN
        && (unique || referencedKeyId == 0
            || referencedKeyId < 0 && !allowUnboundReference)) return false;
    return kind == KeyDescriptor.KIND_FOREIGN || referencedKeyId == 0;
  }

  private static StatusCode fail(
      StatusDetail detail, StatusCode status, CharSequence message) {
    return KeyDescriptorStatus.fail(detail, status, message);
  }
}
