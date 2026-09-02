package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Resolves SQL foreign-key targets and adds their local enforcement indexes. */
final class SqlDescriptorForeignKeyBuilder {
  private final SqlDescriptorForeignKeyTarget target =
      new SqlDescriptorForeignKeyTarget();
  private final SqlDescriptorForeignKeyAssembly assembly =
      new SqlDescriptorForeignKeyAssembly();
  private final int[] localParts = new int[KeyDescriptor.MAXIMUM_PARTS];

  StatusCode build(
      RelationalSession session, SqlCommand command,
      TableDescriptor source, StatusDetail detail) {
    int count = foreignCount(command);
    if (source.secondaryKeyCount() > TableDescriptor.MAXIMUM_SECONDARY_KEYS - count
        || count > TableDescriptor.MAXIMUM_FOREIGN_KEYS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = assembly.begin(source, count);
    if (!status.isOk()) return status;
    for (int constraint = 0;
        status.isOk() && constraint < command.tableConstraintCount(); constraint++) {
      if (command.tableConstraintKind(constraint) == SqlCommand.CONSTRAINT_FOREIGN_KEY) {
        status = buildConstraint(session, command, constraint, source, detail);
      }
    }
    StatusCode released = target.release();
    if (status.isOk()) status = released;
    return status.isOk() ? assembly.finish(source, detail) : status;
  }

  TableDescriptor descriptor() { return assembly.descriptor(); }

  private StatusCode buildConstraint(
      RelationalSession session, SqlCommand command, int constraint,
      TableDescriptor source, StatusDetail detail) {
    int count = command.tableConstraintPartCount(constraint);
    if (count <= 0 || count > localParts.length) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int part = 0; part < count; part++) {
      localParts[part] = source.findColumn(
          command.tableConstraintPartName(constraint, part));
      if (localParts[part] < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = target.resolveConstraint(
        session, command, constraint, command.tableName(), source, localParts, count, detail);
    return status.isOk()
        ? assembly.add(source, command.tableConstraintName(constraint), count, localParts,
            target.referencedKeyId(), constraint, detail)
        : status;
  }

  private static int foreignCount(SqlCommand command) {
    int count = 0;
    for (int index = 0; index < command.tableConstraintCount(); index++) {
      if (command.tableConstraintKind(index) == SqlCommand.CONSTRAINT_FOREIGN_KEY) count++;
    }
    return count;
  }
}
