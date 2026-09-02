package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Retained resolver for one foreign-key target at a time. */
final class SqlDescriptorForeignKeyTarget {
  private final SchemaPin referenced = new SchemaPin();
  private final int[] targetParts = new int[KeyDescriptor.MAXIMUM_PARTS];
  private final SqlDescriptorForeignKeyKeyMatcher matcher =
      new SqlDescriptorForeignKeyKeyMatcher();
  private long referencedKeyId;

  long referencedKeyId() { return referencedKeyId; }

  StatusCode resolveConstraint(
      RelationalSession session, SqlCommand command, int constraint,
      CharSequence sourceName, TableDescriptor source, int[] localParts, int count,
      StatusDetail detail) {
    CharSequence table = command.tableConstraintReferenceTableName(constraint);
    if (sameName(table, sourceName)) {
      for (int part = 0; part < count; part++) {
        targetParts[part] = source.findColumn(
            command.tableConstraintReferencePartName(constraint, part));
        if (targetParts[part] < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return match(source, source, localParts, count, true);
    }
    StatusCode status = open(session, table, detail);
    for (int part = 0; status.isOk() && part < count; part++) {
      targetParts[part] = referenced.descriptor().findColumn(
          command.tableConstraintReferencePartName(constraint, part));
      if (targetParts[part] < 0) status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status.isOk()
        ? match(source, referenced.descriptor(), localParts, count, false) : status;
  }

  StatusCode release() {
    return referenced.isActive() ? referenced.release() : StatusCode.OK;
  }

  private StatusCode open(
      RelationalSession session, CharSequence table, StatusDetail detail) {
    StatusCode status = release();
    return status.isOk() ? session.resolveDescriptor(table, referenced, detail) : status;
  }

  private StatusCode match(
      TableDescriptor source, TableDescriptor target, int[] localParts,
      int count, boolean self) {
    StatusCode status = matcher.match(source, target, localParts, targetParts, count, self);
    if (status.isOk()) referencedKeyId = matcher.keyId();
    return status;
  }

  private static boolean sameName(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
