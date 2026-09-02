package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Retained operand bindings for one correlated descriptor predicate. */
final class SqlDescriptorCorrelatedBindings {
  static final byte CHILD = 1;
  static final byte OUTER = 2;
  static final byte LITERAL = 3;
  static final byte NULL = 4;
  private final SqlDescriptorCorrelatedBindingStorage storage;
  private int count;

  SqlDescriptorCorrelatedBindings() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlDescriptorCorrelatedBindings(SqlRetainedArrayAllocator arrayAllocator) {
    storage = new SqlDescriptorCorrelatedBindingStorage(arrayAllocator);
  }

  SqlDescriptorCorrelatedBindings(SqlSessionShapeBudget budget) {
    storage = new SqlDescriptorCorrelatedBindingStorage(
        SqlRetainedArrayAllocator.STANDARD, budget);
  }

  StatusCode prepare(
      SqlCommand command, TableDescriptor child,
      SqlCommand outerCommand, TableDescriptor outer,
      SqlBooleanPredicateProgram program) {
    count = 0;
    StatusCode status = reserve(program.leafCount());
    for (int leaf = 0; status.isOk() && leaf < program.leafCount(); leaf++) {
      int test = program.leafTest(leaf);
      if (test != SqlBooleanPredicateProgram.TEST_COMPARISON
          && test != SqlBooleanPredicateProgram.TEST_NULL
          && test != SqlBooleanPredicateProgram.TEST_MEMBERSHIP) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
      status = SqlDescriptorCorrelatedOperandBinding.bind(
          storage, command, child, outerCommand, outer, program, leaf,
          SqlBooleanPredicateProgram.PROGRAM_LEFT, true);
      if (status.isOk() && test == SqlBooleanPredicateProgram.TEST_COMPARISON) {
        status = SqlDescriptorCorrelatedOperandBinding.bind(
            storage, command, child, outerCommand, outer, program, leaf,
            SqlBooleanPredicateProgram.PROGRAM_RIGHT, false);
      }
      if (status.isOk() && test == SqlBooleanPredicateProgram.TEST_COMPARISON) {
        status = validateTypes(leaf);
        storage.comparisons[leaf] = program.comparison(leaf);
      } else if (status.isOk()) {
        storage.rightKinds[leaf] = NULL;
        storage.rightColumns[leaf] = -1;
        storage.rightDescriptors[leaf] = 0;
        storage.rightHighs[leaf] = 0;
        storage.rightValues[leaf] = 0;
        storage.comparisons[leaf] = null;
      }
    }
    if (status.isOk()) count = program.leafCount();
    return status;
  }

  private StatusCode validateTypes(int leaf) {
    if (storage.leftKinds[leaf] == NULL
        || storage.rightKinds[leaf] == NULL) return StatusCode.OK;
    int left = storage.leftDescriptors[leaf];
    int right = storage.rightDescriptors[leaf];
    if (!SqlTypeDescriptor.canCompare(left, right)) return StatusCode.DATATYPE_MISMATCH;
    return SqlTypeDescriptor.typeId(left) == SqlTypeDescriptor.TYPE_ID_VARCHAR
            || SqlTypeDescriptor.typeId(right) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  byte leftKind(int leaf) { return storage.leftKinds[leaf]; }
  byte rightKind(int leaf) { return storage.rightKinds[leaf]; }
  int leftColumn(int leaf) { return storage.leftColumns[leaf]; }
  int rightColumn(int leaf) { return storage.rightColumns[leaf]; }
  int leftDescriptor(int leaf) { return storage.leftDescriptors[leaf]; }
  int rightDescriptor(int leaf) { return storage.rightDescriptors[leaf]; }
  long leftHigh(int leaf) { return storage.leftHighs[leaf]; }
  long rightHigh(int leaf) { return storage.rightHighs[leaf]; }
  long leftValue(int leaf) { return storage.leftValues[leaf]; }
  long rightValue(int leaf) { return storage.rightValues[leaf]; }
  SqlComparison comparison(int leaf) { return storage.comparisons[leaf]; }

  byte kind(int leaf, boolean left) {
    return left ? storage.leftKinds[leaf] : storage.rightKinds[leaf];
  }

  int column(int leaf, boolean left) {
    return left ? storage.leftColumns[leaf] : storage.rightColumns[leaf];
  }

  int descriptor(int leaf, boolean left) {
    return left ? storage.leftDescriptors[leaf] : storage.rightDescriptors[leaf];
  }

  long high(int leaf, boolean left) {
    return left ? storage.leftHighs[leaf] : storage.rightHighs[leaf];
  }

  long value(int leaf, boolean left) {
    return left ? storage.leftValues[leaf] : storage.rightValues[leaf];
  }

  boolean correlated() {
    for (int leaf = 0; leaf < count; leaf++) {
      if (storage.leftKinds[leaf] == OUTER || storage.rightKinds[leaf] == OUTER) return true;
    }
    return false;
  }

  StatusCode reserve(int count) {
    return storage.reserve(count);
  }
}
