package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
/** Validates and privately constructs immutable key descriptors. */
final class KeyDescriptorFactory {
  private KeyDescriptorFactory() {
  }

  static StatusCode create(
      long keyId, int kind, boolean unique, ColumnDescriptorSet columns, int[] ordinals,
      long referencedKeyId, CharSequence name, KeyDescriptor.Result result, StatusDetail detail,
      boolean requirePositiveId) {
    KeyDescriptorStatus.reset(result, detail);
    StatusCode status = KeyDescriptorSemanticValidation.validate(
        keyId, kind, unique, columns, ordinals, referencedKeyId, name,
        result, detail, requirePositiveId);
    if (!status.isOk()) return status;
    int[] copied;
    int[] descriptors;
    try {
      copied = ordinals.clone();
      descriptors = new int[ordinals.length];
    } catch (OutOfMemoryError error) {
      return KeyDescriptorStatus.fail(
          detail, StatusCode.RESOURCE_EXHAUSTED, "key descriptor capacity unavailable");
    }
    status = KeyDescriptorPartValidation.validate(
        kind, columns, copied, descriptors, detail);
    if (!status.isOk()) return status;
    return KeyDescriptorPublication.publish(
        keyId, kind, unique, columns, copied, descriptors,
        referencedKeyId, name, result, detail);
  }
}
