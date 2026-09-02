package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.sql.SqlCommand;
import java.util.Arrays;

/** Reusable scratch for freezing SQL key constraints into immutable descriptors. */
final class SqlDescriptorKeyBuilder {
  private final KeyDescriptor.Result primary = new KeyDescriptor.Result();
  private final KeyDescriptor.Result key = new KeyDescriptor.Result();
  private final int[] ordinals = new int[SqlShapeLimits.MAX_KEY_PARTS];
  private KeyDescriptor[] secondary = new KeyDescriptor[0];
  private int secondaryCount;

  StatusCode freeze(
      SqlCommand command, ColumnDescriptorSet columns, StatusDetail detail) {
    reset();
    for (int constraint = 0; constraint < command.tableConstraintCount(); constraint++) {
      StatusCode status = freezeConstraint(command, columns, constraint, detail);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  KeyDescriptor primary() { return primary.value(); }

  KeyDescriptor[] exactSecondary() {
    try {
      return Arrays.copyOf(secondary, secondaryCount);
    } catch (OutOfMemoryError error) {
      return null;
    }
  }

  private void reset() {
    primary.reset();
    Arrays.fill(secondary, 0, secondaryCount, null);
    secondaryCount = 0;
  }

  private StatusCode freezeConstraint(
      SqlCommand command, ColumnDescriptorSet columns, int constraint, StatusDetail detail) {
    int kind = command.tableConstraintKind(constraint);
    if (kind == SqlCommand.CONSTRAINT_CHECK) return StatusCode.OK;
    if (kind == SqlCommand.CONSTRAINT_FOREIGN_KEY) return StatusCode.OK;
    StatusCode status = resolveParts(command, columns, constraint);
    if (!status.isOk()) return status;
    int[] parts = exactParts(command.tableConstraintPartCount(constraint));
    if (parts == null) return StatusCode.RESOURCE_EXHAUSTED;
    KeyDescriptor.Result result = kind == SqlCommand.CONSTRAINT_PRIMARY_KEY ? primary : key;
    status = create(command, columns, constraint, kind, parts, result, detail);
    if (!status.isOk()) return status;
    return result != key || appendSecondary(key.value())
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private StatusCode resolveParts(
      SqlCommand command, ColumnDescriptorSet columns, int constraint) {
    int count = command.tableConstraintPartCount(constraint);
    for (int part = 0; part < count; part++) {
      int ordinal = columns.find(command.tableConstraintPartName(constraint, part));
      if (ordinal < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      ordinals[part] = ordinal;
    }
    return StatusCode.OK;
  }

  private static StatusCode create(
      SqlCommand command, ColumnDescriptorSet columns, int constraint,
      int kind, int[] parts, KeyDescriptor.Result result, StatusDetail detail) {
    CharSequence name = command.tableConstraintName(constraint);
    int descriptorKind = kind == SqlCommand.CONSTRAINT_PRIMARY_KEY
        ? KeyDescriptor.KIND_PRIMARY : KeyDescriptor.KIND_UNIQUE;
    return name.length() == 0
        ? KeyDescriptor.create(1 + constraint, descriptorKind, true,
            columns, parts, 0, result, detail)
        : KeyDescriptor.createNamed(1 + constraint, descriptorKind, true,
            columns, parts, 0, name, result, detail);
  }

  private boolean appendSecondary(KeyDescriptor descriptor) {
    if (secondaryCount == secondary.length && !growSecondary()) return false;
    secondary[secondaryCount++] = descriptor;
    return true;
  }

  private boolean growSecondary() {
    int capacity = Math.min(SqlShapeLimits.MAX_SECONDARY_INDEXES,
        Math.max(4, secondary.length * 2));
    try {
      secondary = Arrays.copyOf(secondary, capacity);
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private int[] exactParts(int count) {
    try {
      return Arrays.copyOf(ordinals, count);
    } catch (OutOfMemoryError error) {
      return null;
    }
  }
}
