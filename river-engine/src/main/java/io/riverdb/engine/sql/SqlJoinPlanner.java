package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlCommand;

/** Deterministic bounded cost choice over the existing SQL-order join chain. */
final class SqlJoinPlanner {
  void select(SqlCommand command, BoundSqlStatement bound) {
    bound.resetJoinEstimates();
    if (!SqlJoinPredicateClassifier.totalJoinOrder(command)
        || !statisticsAvailable(command, bound)) return;
    long outerRows = bound.joinStatistics(0).rowCount();
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      long innerRows = bound.joinStatistics(stage + 1).rowCount();
      long estimated = estimateStage(bound, stage, outerRows, innerRows);
      if (command.joinChain().isLeft(stage) && estimated < outerRows) {
        estimated = outerRows;
      }
      bound.setJoinEstimatedRows(stage, estimated);
      outerRows = estimated;
    }
    chooseStrategy(bound);
  }

  private static void chooseStrategy(BoundSqlStatement bound) {
    int stage = bound.physicalJoinStrategyStage();
    if (stage < 0) return;
    int strategy = bound.joinStrategy(stage);
    long outerRows = stage == 0
        ? bound.joinStatistics(0).rowCount()
        : bound.joinEstimatedRows(stage - 1);
    long innerRows = bound.joinStatistics(stage + 1).rowCount();
    long outputRows = bound.joinEstimatedRows(stage);
    long nested = nestedCost(bound, stage, outerRows, innerRows, outputRows);
    long hash = saturatedAdd(saturatedAdd(outerRows, innerRows), outputRows);
    long merge = mergeCost(bound, stage, outerRows, innerRows, outputRows);
    if (strategy == SqlJoinStrategy.MERGE && hash < merge && hash < nested) {
      bound.setJoinStrategy(
          stage,
          SqlJoinStrategy.HASH,
          bound.joinStrategyOuterRole(stage),
          bound.joinStrategyOuterColumn(stage),
          bound.joinStrategyInnerColumn(stage));
    } else {
      long selected = strategy == SqlJoinStrategy.HASH ? hash : merge;
      if (nested <= selected) bound.clearJoinStrategy(stage);
    }
  }

  private static long nestedCost(
      BoundSqlStatement bound,
      int stage,
      long outerRows,
      long innerRows,
      long outputRows) {
    int inner = bound.joinAccessInnerColumn(stage);
    if (inner < 0) return saturatedMultiply(outerRows, innerRows);
    if (inner == 0 || bound.joinRole(stage + 1).hasUniqueIndexOn(inner)) {
      return outerRows;
    }
    if (bound.joinRole(stage + 1).hasIndexOn(inner)) return outputRows;
    return saturatedMultiply(outerRows, innerRows);
  }

  private static long mergeCost(
      BoundSqlStatement bound,
      int stage,
      long outerRows,
      long innerRows,
      long outputRows) {
    int inner = bound.joinStrategyInnerColumn(stage);
    boolean ordered = inner == 0 || inner > 0
        && bound.joinRole(stage + 1).hasIndexOn(inner);
    long innerCost = ordered
        ? innerRows : saturatedMultiply(innerRows, logarithm(innerRows));
    return saturatedAdd(saturatedAdd(outerRows, innerCost), outputRows);
  }

  private static long estimateStage(
      BoundSqlStatement bound,
      int stage,
      long outerRows,
      long innerRows) {
    if (outerRows == 0 || innerRows == 0) return 0;
    int outerRole = bound.joinAccessOuterRole(stage);
    int outerColumn = bound.joinAccessOuterColumn(stage);
    int innerColumn = bound.joinAccessInnerColumn(stage);
    if (innerColumn < 0 && bound.joinStrategy(stage) != SqlJoinStrategy.NESTED_LOOP) {
      outerRole = bound.joinStrategyOuterRole(stage);
      outerColumn = bound.joinStrategyOuterColumn(stage);
      innerColumn = bound.joinStrategyInnerColumn(stage);
    }
    if (outerRole < 0 || outerColumn < 0 || innerColumn < 0) {
      return saturatedMultiply(outerRows, innerRows);
    }
    TableStatistics outer = bound.joinStatistics(outerRole);
    TableStatistics inner = bound.joinStatistics(stage + 1);
    long distinct = Math.max(
        outer.distinctCount(outerColumn), inner.distinctCount(innerColumn));
    return distinct <= 0
        ? 0 : Math.max(1, saturatedMultiply(outerRows, innerRows) / distinct);
  }

  private static boolean statisticsAvailable(
      SqlCommand command, BoundSqlStatement bound) {
    for (int role = 0; role < command.joinChain().roleCount(); role++) {
      TableStatistics statistics = bound.joinStatistics(role);
      if (statistics == null || !statistics.availableFor(bound.joinRole(role))) {
        return false;
      }
    }
    return true;
  }

  private static long logarithm(long value) {
    if (value <= 1) return 1;
    return Long.SIZE - Long.numberOfLeadingZeros(value - 1);
  }

  private static long saturatedMultiply(long left, long right) {
    if (left == 0 || right == 0) return 0;
    return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
  }

  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}
