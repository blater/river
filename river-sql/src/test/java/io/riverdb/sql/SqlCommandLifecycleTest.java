package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlCommandLifecycleTest {
  @Test
  void resetsAcrossSuccessFailureAndIncompatibleReuse() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO labels VALUES (1, 'first')", command));
    assertTrue(command.isAvailable());
    assertEquals(SqlCommandType.INSERT, command.type());
    assertEquals(1, command.insertRowCount());

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE TABLE broken (id BIGINT PRIMARY KEY)", command));
    assertFalse(command.isAvailable());

    assertEquals(StatusCode.OK, parser.parse("BEGIN SERIALIZABLE", command));
    assertTrue(command.isAvailable());
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertTrue(command.isSerializableTransaction());
    assertEquals(0, command.insertRowCount());
    assertEquals(0, command.columnCount());
    assertEquals(0, command.wherePredicates().leafCount());
  }

  @Test
  void nullInputsInvalidateNonNullDestinationButNullDestinationDoesNothing() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();
    String nested = "SELECT d.id FROM (SELECT id FROM accounts) d";

    assertEquals(StatusCode.OK, parser.parse("COMMIT", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse((String) null, command));
    assertFalse(command.isAvailable());

    assertEquals(StatusCode.OK, parser.parseQuery(nested, query, command));
    assertEquals(2, query.blockCount());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(null, query, command));
    assertFalse(command.isAvailable());
    assertEquals(0, query.blockCount());

    assertEquals(StatusCode.OK, parser.parseQuery(nested, query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(nested, null, command));
    assertFalse(command.isAvailable());

    assertEquals(StatusCode.OK, parser.parseQuery(nested, query, command));
    assertEquals(2, query.blockCount());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SELECT id FROM accounts", query, null));
    assertEquals(0, query.blockCount());
    assertFalse(query.isExplain());
  }

  @Test
  void nestedCompilationFailuresInvalidatePriorSuccessfulCommand() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();

    assertFailureAfterSuccess(
        parser,
        query,
        command,
        StatusCode.INVALID_EXTERNAL_INPUT,
        "SELECT d.region FROM (SELECT id FROM accounts) d");
    assertFailureAfterSuccess(
        parser,
        query,
        command,
        StatusCode.FEATURE_NOT_SUPPORTED,
        "SELECT id FROM accounts WHERE balance = "
            + "(SELECT id, balance FROM lookup WHERE id=7)");
    assertFailureAfterSuccess(
        parser,
        query,
        command,
        StatusCode.FEATURE_NOT_SUPPORTED,
        "SELECT id FROM accounts WHERE EXISTS (SELECT id, balance FROM lookup)");
    assertFailureAfterSuccess(
        parser,
        query,
        command,
        StatusCode.FEATURE_NOT_SUPPORTED,
        "SELECT id FROM accounts WHERE id IN (SELECT id, balance FROM lookup)");
  }

  @Test
  void compileViewPublishesOnlyAfterPostCopyValidationAndMutation() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand outer = new SqlCommand();
    SqlCommand view = new SqlCommand();

    assertEquals(StatusCode.OK, parser.parse("SELECT missing FROM events", outer));
    assertEquals(StatusCode.OK, parser.parse("SELECT id FROM events", view));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, query.compileView(outer, view, outer));
    assertFalse(outer.isAvailable());

    assertEquals(StatusCode.OK, parser.parse("SELECT * FROM events", outer));
    assertEquals(StatusCode.OK, parser.parse("SELECT id FROM events", view));
    assertEquals(StatusCode.OK, query.compileView(outer, view, outer));
    assertTrue(outer.isAvailable());
    assertEquals(SqlCommandType.SCAN, outer.type());
    assertFalse(outer.isSelectAll());
    assertEquals(1, outer.columnCount());

    assertEquals(StatusCode.OK, parser.parse("SELECT id FROM events", view));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, query.compileView(outer, view, view));
    assertFalse(view.isAvailable());
    assertEquals(0, query.blockCount());
  }

  @Test
  void directCompilerFailuresInvalidateDestinationAtTheirOwnBoundary() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();

    assertEquals(StatusCode.OK, parser.parse("COMMIT", command));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, query.compileDerived(command));
    assertFalse(command.isAvailable());
  }

  @Test
  void nestedGraphParseFailureClearsTopologyAndAllowsReuse() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id FROM lookup WHERE lookup.id=accounts.id)",
            query,
            command));
    assertTrue(command.isAvailable());
    assertEquals(2, query.blockCount());
    assertEquals(1, query.edgeCount());

    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id, region FROM lookup)",
            query,
            command));
    assertFalse(command.isAvailable());
    assertEquals(0, query.blockCount());
    assertEquals(0, query.edgeCount());
    assertEquals(0, query.nestedPlanDepth());

    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE id IN "
                + "(SELECT id FROM lookup WHERE lookup.region=7)",
            query,
            command));
    assertTrue(command.isAvailable());
    assertEquals(2, query.blockCount());
    assertEquals(1, query.edgeCount());
    assertEquals(SqlQuery.SUBQUERY_MEMBERSHIP, query.edgeKind(0));
    assertEquals(0, query.edgeParent(0));
    assertEquals(1, query.edgeChild(0));
    assertEquals(0, query.blockParent(1));
    assertEquals(2, query.blockDepth(1));
  }

  @Test
  void invalidExplainClearsPriorCommandAndQueryPublication() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parseQuery("EXPLAIN SELECT id FROM accounts", query, command));
    assertTrue(query.isExplain());
    assertTrue(command.isAvailable());

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("EXPLAIN", query, command));
    assertFalse(command.isAvailable());
    assertFalse(query.isExplain());
    assertEquals(0, query.blockCount());
  }

  @Test
  void finishIsTheOnlyPublicationPoint() {
    SqlCommand command = new SqlCommand();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, command.finish());
    assertFalse(command.isAvailable());

    command.set(SqlCommandType.COMMIT, 0, 0);
    assertFalse(command.isAvailable());
    assertEquals(StatusCode.OK, command.finish());
    assertTrue(command.isAvailable());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, command.finish());
    assertFalse(command.isAvailable());
  }

  private static void assertFailureAfterSuccess(
      SqlParser parser,
      SqlQuery query,
      SqlCommand command,
      StatusCode expected,
      String invalidSql) {
    assertEquals(StatusCode.OK, parser.parseQuery("SELECT id FROM accounts", query, command));
    assertTrue(command.isAvailable());
    assertEquals(
        expected,
        parser.parseQuery(invalidSql, query, command));
    assertFalse(command.isAvailable());
  }
}
