package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Copies one bounded predicate program without publishing partial state. */
final class SqlPredicateCopy {
  private static final int PROGRAMS_PER_LEAF = 4;

  private SqlPredicateCopy() {}

  static StatusCode copy(
      SqlBooleanPredicateProgram target, SqlBooleanPredicateProgram source) {
    target.reset();
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!SqlPredicateCapacity.ensureCopy(
        target, source.scalarNodeCount, source.leafCount,
        source.booleanNodeCount, source.memberCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    target.scalarNodeCount = source.scalarNodeCount;
    target.leafCount = source.leafCount;
    target.booleanNodeCount = source.booleanNodeCount;
    target.root = source.root;
    System.arraycopy(source.scalarOperators, 0, target.scalarOperators, 0, target.scalarNodeCount);
    System.arraycopy(
        source.scalarOperandHighs, 0, target.scalarOperandHighs, 0, target.scalarNodeCount);
    System.arraycopy(source.scalarOperands, 0, target.scalarOperands, 0, target.scalarNodeCount);
    System.arraycopy(
        source.scalarDescriptors, 0, target.scalarDescriptors, 0, target.scalarNodeCount);
    int programCount = target.leafCount * PROGRAMS_PER_LEAF;
    System.arraycopy(source.programOffsets, 0, target.programOffsets, 0, programCount);
    System.arraycopy(source.programCounts, 0, target.programCounts, 0, programCount);
    System.arraycopy(source.leafTests, 0, target.leafTests, 0, target.leafCount);
    System.arraycopy(source.comparisons, 0, target.comparisons, 0, target.leafCount);
    System.arraycopy(source.leafNegated, 0, target.leafNegated, 0, target.leafCount);
    System.arraycopy(source.subqueryEdges, 0, target.subqueryEdges, 0, target.leafCount);
    System.arraycopy(source.memberOffsets, 0, target.memberOffsets, 0, target.leafCount);
    System.arraycopy(source.memberCounts, 0, target.memberCounts, 0, target.leafCount);
    target.memberCount = source.memberCount;
    if (target.memberValues != null && target.memberCount > 0) {
      System.arraycopy(source.memberValues, 0, target.memberValues, 0, target.memberCount);
      System.arraycopy(source.memberHighs, 0, target.memberHighs, 0, target.memberCount);
      System.arraycopy(
          source.memberDescriptors, 0, target.memberDescriptors, 0, target.memberCount);
      System.arraycopy(source.memberNulls, 0, target.memberNulls, 0, target.memberCount);
      System.arraycopy(source.memberKinds, 0, target.memberKinds, 0, target.memberCount);
    }
    System.arraycopy(
        source.booleanOperators, 0, target.booleanOperators, 0, target.booleanNodeCount);
    System.arraycopy(source.booleanLeft, 0, target.booleanLeft, 0, target.booleanNodeCount);
    System.arraycopy(source.booleanRight, 0, target.booleanRight, 0, target.booleanNodeCount);
    System.arraycopy(source.booleanDepth, 0, target.booleanDepth, 0, target.booleanNodeCount);
    return StatusCode.OK;
  }
}
