package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Admission and unpublished construction for immutable column descriptor sets. */
final class ColumnDescriptorSetFactory {
  private ColumnDescriptorSetFactory() {
  }

  static StatusCode create(
      int[] descriptors,
      int descriptorOffset,
      CharSequence[] names,
      int nameOffset,
      boolean[] nullability,
      int nullabilityOffset,
      int count,
      ColumnDescriptorSet.Result result,
      StatusDetail detail) {
    return create(descriptors, descriptorOffset, names, nameOffset, nullability,
        nullabilityOffset, count, null, result, detail);
  }

  static StatusCode create(
      int[] descriptors,
      int descriptorOffset,
      CharSequence[] names,
      int nameOffset,
      boolean[] nullability,
      int nullabilityOffset,
      int count,
      ColumnConstraintDescriptorSet constraints,
      ColumnDescriptorSet.Result result,
      StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    StatusCode status = validRange(
        descriptors, descriptorOffset, names, nameOffset, nullability, nullabilityOffset,
        count, result, detail);
    if (!status.isOk()) return status;
    if (count > SqlShapeLimits.MAX_TABLE_COLUMNS) {
      return append(fail(detail, StatusCode.RESOURCE_EXHAUSTED,
          "column count exceeds maximum"), detail, count, SqlShapeLimits.MAX_TABLE_COLUMNS);
    }
    int[] copiedTypes;
    byte[] copiedNullability;
    CharSequence[] copiedNames;
    try {
      copiedTypes = new int[count];
      copiedNullability = new byte[count];
      copiedNames = new CharSequence[count];
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "column capacity unavailable");
    }
    for (int index = 0; index < count; index++) {
      copiedTypes[index] = descriptors[descriptorOffset + index];
      copiedNullability[index] = (byte) (nullability[nullabilityOffset + index] ? 1 : 0);
      copiedNames[index] = names[nameOffset + index];
      if (!SqlTypeDescriptor.isValid(copiedTypes[index])) {
        return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid column descriptor");
      }
    }
    ColumnNameTable.Result namesResult;
    try {
      namesResult = new ColumnNameTable.Result();
      status = ColumnNameTableFactory.create(
          copiedNames, 0, count, SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES, namesResult, detail);
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "name descriptor unavailable");
    }
    if (!status.isOk()) return status;
    ColumnNameTable namesTable = namesResult.value();
    long charge = SchemaByteCharge.object(0, 4)
        + SchemaByteCharge.array(Integer.BYTES, count)
        + SchemaByteCharge.array(1, count)
        + namesTable.byteCharge()
        + (constraints == null ? 0 : constraints.byteCharge());
    if (!SchemaByteCharge.fits(charge)) {
      return append(fail(detail, StatusCode.RESOURCE_EXHAUSTED,
          "column descriptor charge exceeds allowed bytes"), detail, charge,
          SchemaByteCharge.MAXIMUM_CHARGE);
    }
    try {
      result.set(new ColumnDescriptorSet(
          copiedTypes, copiedNullability, namesTable, constraints, charge));
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "column descriptor unavailable");
    }
    if (detail != null) detail.set(StatusCode.OK);
    return StatusCode.OK;
  }

  private static StatusCode validRange(
      int[] descriptors,
      int descriptorOffset,
      CharSequence[] names,
      int nameOffset,
      boolean[] nullability,
      int nullabilityOffset,
      int count,
      ColumnDescriptorSet.Result result,
      StatusDetail detail) {
    if (result == null || count < 0 || descriptorOffset < 0 || nameOffset < 0
        || nullabilityOffset < 0 || descriptors == null || names == null || nullability == null
        || descriptorOffset > descriptors.length - count
        || nameOffset > names.length - count
        || nullabilityOffset > nullability.length - count) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid column arrays");
    }
    return StatusCode.OK;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }

  private static StatusCode append(
      StatusCode status, StatusDetail detail, long actual, long allowed) {
    if (detail != null) detail.append(" requested=").append(actual).append(" allowed=").append(allowed);
    return status;
  }
}
