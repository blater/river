package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** One literal or outer-row value supplying a descriptor index bound part. */
final class SqlUniversalDescriptorIndexBinding {
  private SqlBoundBooleanPredicateProgram program;
  private int leaf;
  private int side;
  private int sourceBlock = -1;
  private int sourceRole = -1;
  private int sourceColumn = -1;

  void literal(SqlBoundBooleanPredicateProgram source, int atLeaf, int valueSide) {
    program = source;
    leaf = atLeaf;
    side = valueSide;
    sourceBlock = -1;
    sourceRole = -1;
    sourceColumn = -1;
  }

  void outer(int block, int role, int column) {
    program = null;
    sourceBlock = block;
    sourceRole = role;
    sourceColumn = column;
  }

  StatusCode assign(
      SqlDescriptorPrimaryValues target, int targetColumn, int targetDescriptor,
      SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors) {
    if (program != null) return comparable(target.assign(
        targetColumn, program.descriptor(leaf, side, 0), targetDescriptor,
        program.operandHigh(leaf, side, 0), program.operand(leaf, side, 0)));
    SqlBlockRow source = sourceBlock < 0
        ? rows == null ? null : rows.row(sourceRole)
        : ancestors == null ? null : ancestors.blockRow(sourceBlock, sourceRole);
    if (source == null || source.nullValue(sourceColumn)) return StatusCode.CONFLICT;
    io.riverdb.engine.relational.TableDefinition table = sourceBlock < 0
        ? rows == null ? null : rows.table(sourceRole)
        : ancestors == null ? null : ancestors.table(sourceBlock, sourceRole);
    if (table == null) return StatusCode.CONFLICT;
    int descriptor = table.typeDescriptor(sourceColumn);
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

  boolean nullValue(SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors) {
    if (program != null) return false;
    SqlBlockRow source = sourceBlock < 0
        ? rows == null ? null : rows.row(sourceRole)
        : ancestors == null ? null : ancestors.blockRow(sourceBlock, sourceRole);
    return source == null || source.nullValue(sourceColumn);
  }

  boolean literal() { return program != null; }
  boolean outerFrom(int role) {
    return program == null && sourceBlock < 0 && sourceRole == role;
  }
  int outerColumn() { return sourceColumn; }

  private static StatusCode comparable(StatusCode status) {
    return status == StatusCode.INVALID_EXTERNAL_INPUT
        || status == StatusCode.RESOURCE_EXHAUSTED ? StatusCode.CONFLICT : status;
  }

  void reset() {
    program = null;
    sourceBlock = -1;
    sourceRole = -1;
    sourceColumn = -1;
  }
}
