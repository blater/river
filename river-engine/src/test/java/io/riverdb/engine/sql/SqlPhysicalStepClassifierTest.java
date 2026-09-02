package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlParser;
import org.junit.jupiter.api.Test;

final class SqlPhysicalStepClassifierTest {
  @Test
  void cardinalityPolicyDoesNotTurnAQueryIntoPointAccess() {
    BoundSqlStatement bound = selectBound();
    bound.accessPredicate = 0;
    bound.predicateColumn = 1;
    bound.accessComparison = SqlComparison.EQUAL;

    assertEquals(
        SqlPhysicalStepKind.SCAN_SINGLETON,
        SqlPhysicalStepClassifier.classify(bound, TransactionProgramAction.EXACT_ONE));
    assertEquals(
        SqlPhysicalStepKind.SCAN_SINGLETON,
        SqlPhysicalStepClassifier.classify(bound, TransactionProgramAction.ZERO_OR_ONE));
  }

  @Test
  void primaryEqualityUsesPointPrimaryButRowSetPolicyWins() {
    BoundSqlStatement bound = selectBound();
    bound.accessPredicate = 0;
    bound.predicateColumn = 0;
    bound.accessComparison = SqlComparison.EQUAL;

    assertEquals(
        SqlPhysicalStepKind.POINT_PRIMARY,
        SqlPhysicalStepClassifier.classify(bound, TransactionProgramAction.EXACT_ONE));
    assertEquals(
        SqlPhysicalStepKind.ROW_SET,
        SqlPhysicalStepClassifier.classify(bound, TransactionProgramAction.ROW_SET));
  }

  @Test
  void aggregateAndCommandRemainSeparatePhysicalFamilies() {
    BoundSqlStatement bound = parsedCommand("SELECT COUNT(*) FROM records");
    assertEquals(
        SqlPhysicalStepKind.AGGREGATE,
        SqlPhysicalStepClassifier.classify(bound, TransactionProgramAction.ZERO_OR_ONE));

    bound = parsedCommand("UPDATE records SET value=1 WHERE id=1");
    assertEquals(
        SqlPhysicalStepKind.COMMAND,
        SqlPhysicalStepClassifier.classify(bound, TransactionProgramAction.COMMAND));
  }

  @Test
  void nestedSingletonQueryRemainsAGroupScan() {
    BoundSqlStatement bound = new BoundSqlStatement();
    assertEquals(
        StatusCode.OK,
        new SqlParser().parseQuery(
            "SELECT o.id FROM outer_rows o WHERE EXISTS "
                + "(SELECT i.id FROM inner_rows i WHERE i.id=o.id)",
            bound.query,
            bound.command));
    assertEquals(StatusCode.OK, new SqlBinder().captureExecutableQuery(bound));
    bound.accessPredicate = 0;
    bound.predicateColumn = 0;
    bound.accessComparison = SqlComparison.EQUAL;

    assertEquals(
        SqlPhysicalStepKind.SCAN_SINGLETON,
        SqlPhysicalStepClassifier.classify(bound, TransactionProgramAction.EXACT_ONE));
  }

  private static BoundSqlStatement selectBound() {
    BoundSqlStatement bound = new BoundSqlStatement();
    bound.pointTextColumn = -1;
    assertEquals(StatusCode.OK, new SqlParser().parseQuery(
        "SELECT value FROM records", bound.query, bound.command));
    return bound;
  }

  private static BoundSqlStatement parsedCommand(String sql) {
    BoundSqlStatement bound = new BoundSqlStatement();
    assertEquals(StatusCode.OK, new SqlParser().parse(sql, bound.command));
    return bound;
  }
}
