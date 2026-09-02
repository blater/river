package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleShape;

/** Builds and publishes a validated immutable key descriptor. */
final class KeyDescriptorPublication {
  private KeyDescriptorPublication() {
  }

  static StatusCode publish(
      long keyId, int kind, boolean unique, ColumnDescriptorSet columns,
      int[] ordinals, int[] descriptors, long referencedKeyId, CharSequence name,
      KeyDescriptor.Result result, StatusDetail detail) {
    TupleShape.Result shapeResult;
    try {
      shapeResult = new TupleShape.Result();
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "tuple shape capacity unavailable");
    }
    StatusCode status = TupleShape.create(descriptors, shapeResult);
    if (!status.isOk()) return fail(detail, status, "tuple shape unavailable");
    try {
      TupleShape shape = shapeResult.value();
      String copiedName = name == null ? null : name.toString();
      KeyDescriptor published = new KeyDescriptor(
          keyId, columns, kind, unique, shape, ordinals, referencedKeyId, copiedName);
      if (shape.maximumEncodedBytes() > SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES) {
        return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "key bytes exceed allowed bytes");
      }
      if (!SchemaByteCharge.fits(published.byteCharge())) {
        return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "key charge exceeds allowed bytes");
      }
      result.set(published);
      if (detail != null) detail.set(StatusCode.OK);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "key descriptor unavailable");
    }
  }

  private static StatusCode fail(
      StatusDetail detail, StatusCode status, CharSequence message) {
    return KeyDescriptorStatus.fail(detail, status, message);
  }
}
