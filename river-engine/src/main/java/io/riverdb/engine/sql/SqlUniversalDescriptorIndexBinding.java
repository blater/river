package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** One literal or outer-row value supplying a descriptor index bound part. */
final class SqlUniversalDescriptorIndexBinding {
  private SqlBoundBooleanPredicateProgram program;
  private int leaf;
  private int side;
  private int sourceRole = -1;
  private int sourceColumn = -1;

  void literal(SqlBoundBooleanPredicateProgram source, int atLeaf, int valueSide) {
    program = source;
    leaf = atLeaf;
    side = valueSide;
    sourceRole = -1;
    sourceColumn = -1;
  }

  void outer(int role, int column) {
    program = null;
    sourceRole = role;
    sourceColumn = column;
  }

  StatusCode assign(
      SqlDescriptorPrimaryValues target, int targetColumn, int targetDescriptor,
      SqlUniversalJoinRows rows) {
    if (program != null) return comparable(target.assign(
        targetColumn, program.descriptor(leaf, side, 0), targetDescriptor,
        program.operandHigh(leaf, side, 0), program.operand(leaf, side, 0)));
    SqlBlockRow source = rows.row(sourceRole);
    if (source == null || source.nullValue(sourceColumn)) return StatusCode.CONFLICT;
    int descriptor = rows.table(sourceRole).typeDescriptor(sourceColumn);
    if (!SqlTypeDescriptor.canCompare(descriptor, targetDescriptor)) {
      return StatusCode.CONFLICT;
    }
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && SqlTypeDescriptor.typeId(targetDescriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return comparable(target.buffer().setText(
          targetColumn, targetDescriptor, source.text(sourceColumn), 0,
          source.textLength(sourceColumn)));
    }
    return target.assign(
        targetColumn, descriptor, targetDescriptor,
        source.highValue(sourceColumn), source.value(sourceColumn));
  }

  boolean nullValue(SqlUniversalJoinRows rows) {
    if (program != null) return false;
    SqlBlockRow source = rows == null ? null : rows.row(sourceRole);
    return source == null || source.nullValue(sourceColumn);
  }

  private static StatusCode comparable(StatusCode status) {
    return status == StatusCode.INVALID_EXTERNAL_INPUT
        || status == StatusCode.RESOURCE_EXHAUSTED ? StatusCode.CONFLICT : status;
  }

  void reset() {
    program = null;
    sourceRole = -1;
    sourceColumn = -1;
  }
}
