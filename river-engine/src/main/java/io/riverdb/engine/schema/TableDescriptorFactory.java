package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Admission and unpublished construction for immutable table descriptors. */
final class TableDescriptorFactory {
  private TableDescriptorFactory() {
  }

  static StatusCode create(
      long tableId,
      long schemaId,
      long rowLayoutId,
      long catalogGeneration,
      ColumnDescriptorSet columns,
      KeyDescriptor primary,
      KeyDescriptor[] secondary,
      KeyDescriptor[] foreign,
      TableDescriptor.Result result,
      StatusDetail detail,
      boolean requireBoundKeys) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    if (result == null || columns == null || tableId <= 0 || schemaId < 0 || rowLayoutId <= 0
        || catalogGeneration <= 0) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid table descriptor inputs");
    }
    int secondaryCount = secondary == null ? 0 : secondary.length;
    int foreignCount = foreign == null ? 0 : foreign.length;
    if (secondaryCount > TableDescriptor.MAXIMUM_SECONDARY_KEYS
        || foreignCount > TableDescriptor.MAXIMUM_FOREIGN_KEYS) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "table key count exceeds allowed count");
    }
    KeyDescriptor[] copiedSecondary;
    KeyDescriptor[] copiedForeign;
    TableLayout.Result layout;
    try {
      copiedSecondary = new KeyDescriptor[secondaryCount];
      copiedForeign = new KeyDescriptor[foreignCount];
      if (secondaryCount != 0) {
        System.arraycopy(secondary, 0, copiedSecondary, 0, secondaryCount);
      }
      if (foreignCount != 0) {
        System.arraycopy(foreign, 0, copiedForeign, 0, foreignCount);
      }
      layout = new TableLayout.Result();
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "table descriptor capacity unavailable");
    }
    StatusCode status = TableKeyValidation.validate(
        primary, copiedSecondary, copiedForeign, columns,
        TableDescriptor.MAXIMUM_SECONDARY_KEYS, TableDescriptor.MAXIMUM_FOREIGN_KEYS, detail);
    if (status.isOk()) {
      status = TableKeyIdentityValidation.validate(
          primary, copiedSecondary, copiedForeign, requireBoundKeys, detail);
    }
    if (!status.isOk()) return status;
    status = TableLayout.create(columns, layout, detail);
    if (!status.isOk()) return status;
    long charge = SchemaByteCharge.object(0, 7)
        + SchemaByteCharge.array(Integer.BYTES, columns.count())
        + SchemaByteCharge.array(1, columns.count())
        + SchemaByteCharge.array(Long.BYTES, secondaryCount)
        + SchemaByteCharge.array(Long.BYTES, foreignCount)
        + SchemaByteCharge.array(Integer.BYTES, columns.count())
        + SchemaByteCharge.array(1, columns.count())
        + columns.byteCharge()
        + TableKeyValidation.charge(primary, copiedSecondary, copiedForeign);
    if (!SchemaByteCharge.fits(charge)) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "table descriptor charge exceeds allowed bytes");
    }
    try {
      result.set(new TableDescriptor(
          tableId, schemaId, rowLayoutId, catalogGeneration, columns, primary,
          copiedSecondary, copiedForeign, layout, charge));
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "table descriptor unavailable");
    }
    if (detail != null) detail.set(StatusCode.OK);
    return StatusCode.OK;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }
}
