package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Literal-list membership semantics for a correlated descriptor child predicate. */
final class SqlDescriptorCorrelatedMembership {
  private SqlDescriptorCorrelatedMembership() { }

  static StatusCode validate(
      SqlBooleanPredicateProgram program,
      SqlDescriptorCorrelatedBindings bindings) {
    for (int leaf = 0; leaf < program.leafCount(); leaf++) {
      if (program.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_MEMBERSHIP) continue;
      StatusCode status = validate(program, leaf, bindings.leftDescriptor(leaf));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode validate(
      SqlBooleanPredicateProgram program, int leaf, int descriptor) {
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    for (int member = 0; member < program.leafMemberCount(leaf); member++) {
      if (program.memberNull(leaf, member)) continue;
      int memberDescriptor = program.memberDescriptor(leaf, member);
      if (!SqlTypeDescriptor.canCompare(descriptor, memberDescriptor)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      if (SqlTypeDescriptor.typeId(memberDescriptor)
          == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
    }
    return StatusCode.OK;
  }

  static int evaluate(
      SqlBooleanPredicateProgram program,
      int leaf,
      long high,
      long value,
      int descriptor,
      ExactDecimal128.Scratch decimal) {
    boolean unknown = false;
    for (int member = 0; member < program.leafMemberCount(leaf); member++) {
      if (program.memberNull(leaf, member)) {
        unknown = true;
        continue;
      }
      int memberDescriptor = program.memberDescriptor(leaf, member);
      int compared = SqlNumericTypeRules.isNumeric(descriptor)
              && SqlNumericTypeRules.isNumeric(memberDescriptor)
          ? SqlNumericComparison.compare(
              high,
              value,
              descriptor,
              program.memberHigh(leaf, member),
              program.memberValue(leaf, member),
              memberDescriptor,
              decimal)
          : Long.compare(value, program.memberValue(leaf, member));
      if (compared == 0) return program.leafNegated(leaf) ? 0 : 1;
    }
    return unknown ? -1 : program.leafNegated(leaf) ? 1 : 0;
  }
}
