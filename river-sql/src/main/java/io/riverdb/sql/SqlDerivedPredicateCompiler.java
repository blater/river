package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Rehomes and composes derived Boolean predicates onto the physical command. */
final class SqlDerivedPredicateCompiler {
  private final SqlQuery query;
  private final SqlDerivedColumnResolver columns;
  private final SqlDerivedProjectionCompiler projections;
  private final SqlScalarExpression[] programs = new SqlScalarExpression[4];
  private final int[] booleanMap =
      new int[SqlBooleanPredicateProgram.MAXIMUM_BOOLEAN_NODES];
  private final int[] leafMap = new int[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final long[] memberValues =
      new long[SqlBooleanPredicateProgram.MAXIMUM_MEMBERS];
  private final int[] memberDescriptors =
      new int[SqlBooleanPredicateProgram.MAXIMUM_MEMBERS];
  private final boolean[] memberNulls =
      new boolean[SqlBooleanPredicateProgram.MAXIMUM_MEMBERS];
  private StatusCode copyStatus = StatusCode.OK;

  SqlDerivedPredicateCompiler(
      SqlQuery ownedQuery,
      SqlDerivedColumnResolver columnResolver,
      SqlDerivedProjectionCompiler projectionCompiler) {
    query = ownedQuery;
    columns = columnResolver;
    projections = projectionCompiler;
    for (int index = 0; index < programs.length; index++) {
      programs[index] = new SqlScalarExpression();
    }
  }

  StatusCode copy(SqlCommand destination) {
    SqlBooleanPredicateProgram target = destination.writableWherePredicates();
    target.reset();
    int combined = -1;
    for (int block = query.blockCount() - 1; block >= 0; block--) {
      SqlCommand sourceCommand = query.block(block);
      SqlBooleanPredicateProgram source = sourceCommand.wherePredicates();
      if (!source.isAvailable()) continue;
      copyStatus = StatusCode.OK;
      int root = copyTree(block, sourceCommand, source, destination, target);
      if (root < 0) return copyStatus;
      combined = combined < 0 ? root
          : target.appendBoolean(
              SqlBooleanPredicateProgram.BOOLEAN_AND, combined, root);
      if (combined < 0) return StatusCode.RESOURCE_EXHAUSTED;
    }
    return combined < 0 || target.finish(combined)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  StatusCode copyOrder(SqlCommand root, SqlCommand destination) {
    if (!root.isOrdered()) return StatusCode.OK;
    int projection = SqlDerivedColumnResolver.outputIndex(
        root, root.orderColumnName());
    if (projection >= 0
        && !destination.projectionExpression(projection).isDirectColumnReference()) {
      destination.writableOrderColumnName().copyFrom(root.orderColumnName());
    } else {
      CharSequence ordered = projection >= 0
          ? root.columnName(projection) : root.orderColumnName();
      int resolved = columns.copy(
          0, ordered, destination.writableOrderColumnName());
      if (resolved != 0) return resolutionStatus(resolved);
    }
    destination.setDescendingOrder(root.isDescendingOrder());
    return StatusCode.OK;
  }

  private int copyTree(
      int block,
      SqlCommand sourceCommand,
      SqlBooleanPredicateProgram source,
      SqlCommand destination,
      SqlBooleanPredicateProgram target) {
    clearMaps();
    for (int node = 0; node < source.booleanNodeCount(); node++) {
      int operator = source.booleanOperator(node);
      int left = source.booleanLeft(node);
      int right = source.booleanRight(node);
      if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
        if (leafMap[left] < 0) {
          leafMap[left] = copyLeaf(
              block, sourceCommand, source, left, destination, target);
        }
        left = leafMap[left];
      } else {
        left = booleanMap[left];
        if (operator != SqlBooleanPredicateProgram.BOOLEAN_NOT) {
          right = booleanMap[right];
        }
      }
      int copied = left < 0 ? -1 : target.appendBoolean(operator, left, right);
      if (copied < 0) {
        if (copyStatus.isOk()) copyStatus = StatusCode.RESOURCE_EXHAUSTED;
        return -1;
      }
      booleanMap[node] = copied;
    }
    return booleanMap[source.root()];
  }

  private int copyLeaf(
      int block,
      SqlCommand sourceCommand,
      SqlBooleanPredicateProgram source,
      int leaf,
      SqlCommand destination,
      SqlBooleanPredicateProgram target) {
    for (int program = 0; program < programs.length; program++) {
      StatusCode status = projections.copyPredicateProgram(
          block, source, leaf, program, destination, programs[program]);
      if (!status.isOk()) {
        copyStatus = status;
        return -1;
      }
    }
    int copied = target.appendLeaf(programs[SqlBooleanPredicateProgram.PROGRAM_LEFT]);
    if (copied < 0) {
      copyStatus = StatusCode.RESOURCE_EXHAUSTED;
      return -1;
    }
    int test = source.leafTest(leaf);
    boolean accepted = switch (test) {
      case SqlBooleanPredicateProgram.TEST_COMPARISON -> target.setComparison(
          copied,
          source.comparison(leaf),
          programs[SqlBooleanPredicateProgram.PROGRAM_RIGHT]);
      case SqlBooleanPredicateProgram.TEST_NULL -> target.setNull(
          copied, source.leafNegated(leaf));
      case SqlBooleanPredicateProgram.TEST_TRUTH -> target.setTruth(
          copied, truth(source.comparison(leaf)), source.leafNegated(leaf));
      case SqlBooleanPredicateProgram.TEST_BETWEEN -> target.setBetween(
          copied,
          programs[SqlBooleanPredicateProgram.PROGRAM_LOWER],
          programs[SqlBooleanPredicateProgram.PROGRAM_UPPER],
          source.leafNegated(leaf));
      case SqlBooleanPredicateProgram.TEST_MEMBERSHIP -> copyMembers(
          sourceCommand, source, leaf, destination, target, copied);
      case SqlBooleanPredicateProgram.TEST_BOOLEAN -> target.setBoolean(copied);
      default -> false;
    };
    if (!accepted && copyStatus.isOk()) copyStatus = StatusCode.RESOURCE_EXHAUSTED;
    return accepted ? copied : -1;
  }

  private boolean copyMembers(
      SqlCommand sourceCommand,
      SqlBooleanPredicateProgram source,
      int leaf,
      SqlCommand destination,
      SqlBooleanPredicateProgram target,
      int copied) {
    int count = source.leafMemberCount(leaf);
    for (int member = 0; member < count; member++) {
      long value = source.memberValue(leaf, member);
      int descriptor = source.memberDescriptor(leaf, member);
      if (!source.memberNull(leaf, member)
          && SqlTypeDescriptor.typeId(descriptor)
              == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        value = destination.copyTextFrom(sourceCommand, value);
        if (value == SqlCommand.INVALID_TEXT_HANDLE) {
          copyStatus = StatusCode.RESOURCE_EXHAUSTED;
          clearMembers(member);
          return false;
        }
      }
      memberValues[member] = value;
      memberDescriptors[member] = descriptor;
      memberNulls[member] = source.memberNull(leaf, member);
    }
    boolean accepted = target.setMembership(
        copied,
        memberValues,
        memberDescriptors,
        memberNulls,
        count,
        source.leafNegated(leaf));
    clearMembers(count);
    return accepted;
  }

  private void clearMaps() {
    for (int node = 0; node < booleanMap.length; node++) booleanMap[node] = -1;
    for (int leaf = 0; leaf < leafMap.length; leaf++) leafMap[leaf] = -1;
  }

  private void clearMembers(int count) {
    for (int member = 0; member < count; member++) {
      memberValues[member] = 0;
      memberDescriptors[member] = 0;
      memberNulls[member] = false;
    }
  }

  private static int truth(SqlComparison comparison) {
    return comparison == SqlComparison.EQUAL
        ? SqlBooleanPredicateProgram.TRUTH_TRUE
        : comparison == SqlComparison.NOT_EQUAL
            ? SqlBooleanPredicateProgram.TRUTH_FALSE
            : SqlBooleanPredicateProgram.TRUTH_UNKNOWN;
  }

  private static StatusCode resolutionStatus(int resolved) {
    return resolved > 0
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
