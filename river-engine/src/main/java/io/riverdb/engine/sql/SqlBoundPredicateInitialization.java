package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Copies validated predicate topology after all bound storage is admitted. */
final class SqlBoundPredicateInitialization {
  private static final int PROGRAMS_PER_LEAF = 4;

  private SqlBoundPredicateInitialization() { }

  static StatusCode begin(
      SqlBoundBooleanPredicateProgram target, SqlBooleanPredicateProgram source) {
    StatusCode status = SqlBoundPredicateCapacity.reserve(target, source);
    if (!status.isOk()) return status;
    target.leafCount = source.leafCount();
    target.memberCount = source.memberCount();
    target.booleanNodeCount = source.booleanNodeCount();
    target.root = source.root();
    int memberOffset = 0;
    for (int leaf = 0; leaf < target.leafCount; leaf++) {
      memberOffset = copyLeaf(target, source, leaf, memberOffset);
    }
    for (int node = 0; node < target.booleanNodeCount; node++) {
      target.booleanOperators[node] = (byte) source.booleanOperator(node);
      target.booleanLeft[node] = source.booleanLeft(node);
      target.booleanRight[node] = source.booleanRight(node);
    }
    return StatusCode.OK;
  }

  private static int copyLeaf(
      SqlBoundBooleanPredicateProgram target,
      SqlBooleanPredicateProgram source,
      int leaf,
      int memberOffset) {
    target.tests[leaf] = (byte) source.leafTest(leaf);
    target.comparisons[leaf] = source.comparison(leaf);
    target.negated[leaf] = source.leafNegated(leaf);
    target.subqueryEdges[leaf] = source.subqueryEdge(leaf);
    target.memberOffsets[leaf] = memberOffset;
    target.memberCounts[leaf] = source.leafMemberCount(leaf);
    for (int member = 0; member < target.memberCounts[leaf]; member++) {
      int slot = memberOffset + member;
      target.members[slot] = source.memberValue(leaf, member);
      target.memberHighs[slot] = source.memberHigh(leaf, member);
      target.memberDescriptors[slot] = source.memberDescriptor(leaf, member);
      target.memberNulls[slot] = source.memberNull(leaf, member);
    }
    target.prepareLeafPrograms(leaf);
    return memberOffset + target.memberCounts[leaf];
  }
}
