package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlCommand;

/** Deterministic bounded cost choice over the existing SQL-order join chain. */
final class SqlJoinPlanner {
  void select(SqlCommand command, SqlBoundJoinContext context) {
    context.resetEstimates();
    if (!SqlJoinPredicateClassifier.totalJoinOrder(command)
        || !statisticsAvailable(command, context)) return;
    long outerRows = context.statistics(0).rowCount();
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      long innerRows = context.statistics(stage + 1).rowCount();
      long estimated = estimateStage(context, stage, outerRows, innerRows);
      if (command.joinChain().isLeft(stage) && estimated < outerRows) {
        estimated = outerRows;
      }
      context.setEstimatedRows(stage, estimated);
      outerRows = estimated;
    }
    chooseStrategy(context);
  }

  private static void chooseStrategy(SqlBoundJoinContext context) {
    int stage = context.physicalStrategyStage();
    if (stage < 0) return;
    int strategy = context.strategy(stage);
    long outerRows = stage == 0
        ? context.statistics(0).rowCount()
        : context.estimatedRows(stage - 1);
    long innerRows = context.statistics(stage + 1).rowCount();
    long outputRows = context.estimatedRows(stage);
    long nested = nestedCost(context, stage, outerRows, innerRows, outputRows);
    long hash = saturatedAdd(saturatedAdd(outerRows, innerRows), outputRows);
    long merge = mergeCost(context, stage, outerRows, innerRows, outputRows);
    if (strategy == SqlJoinStrategy.MERGE && hash < merge && hash < nested) {
      context.setStrategy(
          stage,
          SqlJoinStrategy.HASH,
          context.strategyOuterRole(stage),
          context.strategyOuterColumn(stage),
          context.strategyInnerColumn(stage));
    } else {
      long selected = strategy == SqlJoinStrategy.HASH ? hash : merge;
      if (nested <= selected) context.clearStrategy(stage);
    }
  }

  private static long nestedCost(
      SqlBoundJoinContext context,
      int stage,
      long outerRows,
      long innerRows,
      long outputRows) {
    int inner = context.accessInnerColumn(stage);
    if (inner < 0) return saturatedMultiply(outerRows, innerRows);
    if (inner == 0 || context.table(stage + 1).hasUniqueIndexOn(inner)) {
      return outerRows;
    }
    if (context.table(stage + 1).hasIndexOn(inner)) return outputRows;
    return saturatedMultiply(outerRows, innerRows);
  }

  private static long mergeCost(
      SqlBoundJoinContext context,
      int stage,
      long outerRows,
      long innerRows,
      long outputRows) {
    int inner = context.strategyInnerColumn(stage);
    boolean ordered = inner == 0 || inner > 0
        && context.table(stage + 1).hasIndexOn(inner);
    long innerCost = ordered
        ? innerRows : saturatedMultiply(innerRows, logarithm(innerRows));
    return saturatedAdd(saturatedAdd(outerRows, innerCost), outputRows);
  }

  private static long estimateStage(
      SqlBoundJoinContext context,
      int stage,
      long outerRows,
      long innerRows) {
    if (outerRows == 0 || innerRows == 0) return 0;
    int outerRole = context.accessOuterRole(stage);
    int outerColumn = context.accessOuterColumn(stage);
    int innerColumn = context.accessInnerColumn(stage);
    if (innerColumn < 0 && context.strategy(stage) != SqlJoinStrategy.NESTED_LOOP) {
      outerRole = context.strategyOuterRole(stage);
      outerColumn = context.strategyOuterColumn(stage);
      innerColumn = context.strategyInnerColumn(stage);
    }
    if (outerRole < 0 || outerColumn < 0 || innerColumn < 0) {
      return saturatedMultiply(outerRows, innerRows);
    }
    TableStatistics outer = context.statistics(outerRole);
    TableStatistics inner = context.statistics(stage + 1);
    long distinct = Math.max(
        outer.distinctCount(outerColumn), inner.distinctCount(innerColumn));
    return distinct <= 0
        ? 0 : Math.max(1, saturatedMultiply(outerRows, innerRows) / distinct);
  }

  private static boolean statisticsAvailable(
      SqlCommand command, SqlBoundJoinContext context) {
    for (int role = 0; role < command.joinChain().roleCount(); role++) {
      TableStatistics statistics = context.statistics(role);
      if (statistics == null || !statistics.availableFor(context.table(role))) {
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
