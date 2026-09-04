package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path lexical-scope evidence for nested predicates and scalar results. */
final class SqlNestedScopeTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e45535453434f50L, 0x4554455354303031L);

  @Test
  void evaluatesThreeScopesComputedProjectionAndOwnedUnicode(@TempDir Path root) {
    Fixture fixture = open(root);
    createFixture(fixture.session, fixture.result);

    assertRows(
        fixture.session,
        "SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT m.id FROM middle_scope m WHERE m.outer_id=o.id AND "
            + "o.outer_only=(SELECT i.value+o.delta FROM inner_scope i "
            + "WHERE i.middle_id=m.id))",
        1);
    assertRows(
        fixture.session,
        "SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT i.id FROM inner_scope i "
            + "WHERE outer_only=i.value+o.delta)",
        1);
    assertRows(
        fixture.session,
        "SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT m.id FROM middle_scope m WHERE m.outer_id=o.id AND EXISTS "
            + "(SELECT i.id FROM inner_scope i "
            + "WHERE i.middle_id=m.id AND nearest_only=i.value))",
        1);
    assertRows(
        fixture.session,
        "SELECT o.id FROM outer_scope o WHERE o.label IN "
            + "(SELECT o.label FROM inner_scope i WHERE i.id=o.id)",
        1, 2);

    fixture.close();
  }

  @Test
  void appliesShadowingAndRejectsAmbiguousOrInvisibleScopes(@TempDir Path root) {
    Fixture fixture = open(root);
    createFixture(fixture.session, fixture.result);

    assertRows(
        fixture.session,
        "SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT i.id FROM inner_scope i "
            + "WHERE i.id=o.id AND shared=7)",
        1);
    assertExplain(
        fixture.session,
        "EXPLAIN SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT o.id FROM inner_scope o WHERE o.id=1)",
        StatusCode.OK);
    assertExplain(
        fixture.session,
        "EXPLAIN SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT o.id FROM inner_scope o WHERE o.outer_only=1)",
        StatusCode.INVALID_EXTERNAL_INPUT);
    assertExplain(
        fixture.session,
        "EXPLAIN SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT m.id FROM middle_scope m WHERE i.id=m.id AND EXISTS "
            + "(SELECT i.id FROM inner_scope i WHERE i.middle_id=m.id))",
        StatusCode.INVALID_EXTERNAL_INPUT);
    assertExplain(
        fixture.session,
        "EXPLAIN SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT a.id FROM inner_scope a WHERE a.id=o.id) OR EXISTS "
            + "(SELECT b.id FROM inner_scope b WHERE a.id=b.id)",
        StatusCode.INVALID_EXTERNAL_INPUT);
    fixture.close();
  }

  @Test
  void bindsJoinedRolesAndAllFamilyTypedNulls(@TempDir Path root) {
    Fixture fixture = open(root);
    createFixture(fixture.session, fixture.result);

    assertJoinedResolver(fixture);

    String[] columns = {
        "amount", "flag", "label", "day_value", "time_value",
        "observed", "captured"
    };
    for (String column : columns) {
      assertExplain(
          fixture.session,
          "EXPLAIN SELECT o.id FROM typed_scope o WHERE o." + column
              + "=(SELECT NULL FROM inner_scope i WHERE i.id=o.id)",
          StatusCode.OK);
    }

    fixture.close();
  }

  @Test
  void executesJoinedParentsAndChildrenThroughPublicSession(@TempDir Path root) {
    Fixture fixture = open(root);
    createFixture(fixture.session, fixture.result);

    assertRows(
        fixture.session,
        "SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
            + "ON a.id=b.id WHERE EXISTS "
            + "(SELECT i.id FROM inner_scope i "
            + "WHERE i.id=a.id AND i.value=b.b_only)",
        1, 2);
    assertRows(
        fixture.session,
        "SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
            + "ON a.id=b.id AND b.b_only=o.nearest_only WHERE a.id=o.id)",
        1, 2);
    assertExplain(
        fixture.session,
        "EXPLAIN SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
            + "ON a.id=b.id AND c.c_only=b.b_only "
            + "JOIN join_scope_c c ON b.id=c.id WHERE a.id=o.id)",
        StatusCode.INVALID_EXTERNAL_INPUT);
    assertRows(fixture.session, "SELECT id FROM outer_scope", 1, 2);

    fixture.close();
  }

  @Test
  void bindsJoinedGraphScopesWithoutOverwritingPackedPrograms(@TempDir Path root) {
    Fixture fixture = open(root);
    createFixture(fixture.session, fixture.result);
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, fixture.database.createSession(opened));
    RelationalSession relational = opened.session();
    assertEquals(StatusCode.OK, relational.begin(IsolationLevel.REPEATABLE_READ));

    BoundSqlStatement rootJoin = graph(
        "SELECT a.id FROM join_scope_a a WHERE EXISTS "
            + "(SELECT i.id FROM inner_scope i WHERE i.id=a.id)");
    replaceJoinBlock(
        rootJoin,
        0,
        "SELECT a.id FROM join_scope_a a JOIN join_scope_b b ON a.id=b.id",
        true);
    SqlBinder binder = new SqlBinder();
    assertEquals(StatusCode.OK, bindGraph(relational, binder, rootJoin));
    SqlBoundJoinContext rootContext = rootJoin.existingJoinContext(0);
    assertProgramScope(rootContext.onBoolean(0), SqlNestedRowProvider.scope(0, 0));
    assertProgramScope(rootContext.onBoolean(0), SqlNestedRowProvider.scope(0, 1));
    assertEquals(
        StatusCode.OK,
        binder.bindJoinProjection(rootJoin.query.block(0), rootJoin, rootContext));
    assertProgramScope(rootContext.onBoolean(0), SqlNestedRowProvider.scope(0, 0));
    assertProgramScope(rootContext.onBoolean(0), SqlNestedRowProvider.scope(0, 1));

    BoundSqlStatement nearest = graph(
        "SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT m.id FROM middle_scope m WHERE EXISTS "
            + "(SELECT a.id FROM join_scope_a a WHERE a.id=m.id))");
    replaceJoinBlock(
        nearest,
        2,
        "SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
            + "ON a.id=b.id AND nearest_only=b.b_only "
            + "AND m.shared=b.b_only WHERE a.id=m.id",
        false);
    assertEquals(StatusCode.OK, bindGraph(relational, new SqlBinder(), nearest));
    assertEquals(1, nearest.executableQuery.block(2).correlationScope());

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        bindGraph(
            relational,
            new SqlBinder(),
            joinedRoot(
                "SELECT a.id FROM join_scope_a a WHERE EXISTS "
                    + "(SELECT i.id FROM inner_scope i WHERE i.id=a.id)",
                "SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
                    + "ON a.id=b.id AND c.c_only=b.b_only "
                    + "JOIN join_scope_c c ON b.id=c.id")));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        bindGraph(
            relational,
            new SqlBinder(),
            joinedChild(
                "SELECT o.id FROM outer_scope o WHERE EXISTS "
                    + "(SELECT a.id FROM join_scope_a a WHERE a.id=o.id)",
                "SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
                    + "ON a.id=b.id AND id=1 WHERE a.id=o.id")));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        bindGraph(
            relational,
            new SqlBinder(),
            joinedChild(
                "SELECT o.id FROM outer_scope o WHERE EXISTS "
                    + "(SELECT a.id FROM join_scope_a a WHERE a.id=o.id)",
                "SELECT o.id FROM join_scope_a o JOIN join_scope_b b "
                    + "ON o.id=b.id AND o.outer_only=1 WHERE o.id=1")));

    BoundSqlStatement siblings = graph(
        "SELECT o.id FROM outer_scope o WHERE EXISTS "
            + "(SELECT a.id FROM join_scope_a a WHERE a.id=o.id) OR EXISTS "
            + "(SELECT c.id FROM join_scope_c c WHERE c.id=o.id)");
    replaceJoinBlock(
        siblings,
        1,
        "SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
            + "ON a.id=b.id AND a.a_only=b.b_only WHERE a.id=o.id",
        false);
    replaceJoinBlock(
        siblings,
        2,
        "SELECT c.id FROM join_scope_c c JOIN join_scope_b d "
            + "ON c.id=d.id AND c.c_only=d.b_only WHERE c.id=o.id",
        false);
    assertEquals(StatusCode.OK, bindGraph(relational, new SqlBinder(), siblings));
    SqlBoundJoinContext first = siblings.existingJoinContext(1);
    SqlBoundJoinContext second = siblings.existingJoinContext(2);
    assertNotSame(first, second);
    assertNotSame(first.onBoolean(0), second.onBoolean(0));
    assertProgramScope(first.onBoolean(0), SqlNestedRowProvider.scope(1, 1));
    assertProgramScope(second.onBoolean(0), SqlNestedRowProvider.scope(2, 1));
    for (int role = 0; role < 2; role++) {
      assertSame(siblings.executableQuery.block(1).table(role), first.table(role));
      assertSame(siblings.executableQuery.block(2).table(role), second.table(role));
    }
    SqlTemporalContext temporal = new SqlTemporalContext();
    assertEquals(StatusCode.OK, temporal.beginStatement());
    SqlSubqueryGraphExecution execution = new SqlSubqueryGraphExecution(
        relational, siblings, new SqlExpressionEvaluator(), temporal);
    assertEquals(StatusCode.OK, execution.prepare());
    assertNotSame(execution.joinPredicates(1), execution.joinPredicates(2));
    assertEquals(StatusCode.OK, execution.close());
    temporal.finishStatement();
    siblings.reset();
    assertNull(first.table(0));
    assertNull(second.table(0));

    assertEquals(StatusCode.OK, relational.commit(new TransactionOutcome()));
    fixture.close();
  }

  private static BoundSqlStatement graph(String sql) {
    BoundSqlStatement bound = new BoundSqlStatement();
    assertEquals(
        StatusCode.OK,
        new SqlParser().parseQuery(sql, bound.query, bound.command),
        sql);
    int root = bound.query.sourceBlockCount() - 1;
    assertEquals(
        StatusCode.OK,
        bound.executableQuery.capture(bound.query.block(root), bound.query),
        sql);
    return bound;
  }

  private static BoundSqlStatement joinedRoot(String graphSql, String joinSql) {
    BoundSqlStatement bound = graph(graphSql);
    replaceJoinBlock(bound, 0, joinSql, true);
    return bound;
  }

  private static BoundSqlStatement joinedChild(String graphSql, String joinSql) {
    BoundSqlStatement bound = graph(graphSql);
    replaceJoinBlock(bound, 1, joinSql, false);
    return bound;
  }

  private static void replaceJoinBlock(
      BoundSqlStatement bound,
      int block,
      String sql,
      boolean retainWhere) {
    // Remove this internal replacement when joined graph blocks pass parser admission.
    SqlCommand replacement = new SqlCommand();
    assertEquals(StatusCode.OK, new SqlParser().parse(sql, replacement), sql);
    if (retainWhere) {
      replacement.wherePredicates().copyFrom(
          bound.query.block(block).wherePredicates());
    }
    assertEquals(StatusCode.OK, bound.query.block(block).copyBlockFrom(replacement));
    assertEquals(StatusCode.OK, bound.executableQuery.block(block).capture(replacement));
  }

  private static StatusCode bindGraph(
      RelationalSession session, SqlBinder binder, BoundSqlStatement bound) {
    int root = bound.query.sourceBlockCount() - 1;
    SqlCommand command = bound.query.block(root);
    StatusCode status;
    if (command.joinChain() == null) {
      status = new SqlBindingTableResolver().resolve(
          session, command.tableName(), bound.table);
    } else {
      status = binder.resolveJoinRoles(
          session, command, bound.joinContext(root), null, true);
    }
    return status.isOk() ? binder.bindQueryBlocks(session, bound) : status;
  }

  private static void assertProgramScope(
      SqlBoundBooleanPredicateProgram program, int expected) {
    for (int leaf = 0; leaf < program.leafCount(); leaf++) {
      for (int side = 0; side < 4; side++) {
        for (int node = 0; node < program.nodeCount(leaf, side); node++) {
          if (program.scope(leaf, side, node) == expected) return;
        }
      }
    }
    assertEquals(expected, -1, "missing packed scope");
  }

  private static void assertJoinedResolver(Fixture fixture) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, fixture.database.createSession(opened));
    RelationalSession relational = opened.session();
    assertEquals(StatusCode.OK, relational.begin(IsolationLevel.REPEATABLE_READ));
    TableDefinition left = new TableDefinition();
    TableDefinition right = new TableDefinition();
    SqlBindingTableResolver tables = new SqlBindingTableResolver();
    assertEquals(StatusCode.OK, tables.resolve(relational, "join_scope_a", left));
    assertEquals(StatusCode.OK, tables.resolve(relational, "join_scope_b", right));

    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery syntax = new SqlQuery();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT a.id FROM join_scope_a a JOIN join_scope_b b "
                + "ON a.id=b.id",
            syntax,
            command));
    BoundSqlQuery query = new BoundSqlQuery();
    assertEquals(StatusCode.OK, query.capture(command, syntax));
    query.beginBinding(left);
    query.block(0).bindRoleTable(1, right);

    SqlNestedColumnResolver resolver = new SqlNestedColumnResolver();
    assertEquals(StatusCode.OK, resolver.resolve(query, 0, "b", "b_only"));
    assertEquals(0, resolver.block());
    assertEquals(1, resolver.role());
    assertEquals(1, resolver.column());
    assertEquals(StatusCode.OK, resolver.resolve(query, 0, "", "b_only"));
    assertEquals(1, resolver.role());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        resolver.resolve(query, 0, "", "id"));
    assertEquals(0, SqlNestedRowProvider.scope(0, 0));
    assertEquals(32, SqlNestedRowProvider.scope(0, 1));
    assertEquals(255, SqlNestedRowProvider.scope(31, 7));
    assertEquals(31, SqlNestedRowProvider.block(255));
    assertEquals(7, SqlNestedRowProvider.role(255));
    assertEquals(2_016, SqlNestedRowProvider.scope(0, 63));
    assertEquals(0, SqlNestedRowProvider.block(2_016));
    assertEquals(63, SqlNestedRowProvider.role(2_016));
    assertEquals(StatusCode.OK, relational.commit(new TransactionOutcome()));
  }

  private static Fixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            databaseRequest(16),
            root, DATABASE, WalGeneration.of(1), 16, opened));
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessionResult));
    return new Fixture(opened.database(), sessionResult.session());
  }

  private static void createFixture(SqlSession session, SqlExecutionResult result) {
    execute(
        session,
        result,
        "CREATE TABLE outer_scope (id BIGINT PRIMARY KEY,outer_only BIGINT,"
            + "shared BIGINT,delta BIGINT,label VARCHAR(32),nearest_only BIGINT)");
    execute(
        session,
        result,
        "CREATE TABLE typed_scope (id BIGINT PRIMARY KEY,amount DECIMAL(8,2),"
            + "flag BOOLEAN,label VARCHAR(32),day_value DATE,time_value TIME(6),"
            + "observed TIMESTAMP(6),captured TIMESTAMP(6) WITH TIME ZONE)");
    execute(
        session,
        result,
        "CREATE TABLE middle_scope "
            + "(id BIGINT PRIMARY KEY,outer_id BIGINT,shared BIGINT,"
            + "nearest_only BIGINT)");
    execute(
        session,
        result,
        "CREATE TABLE inner_scope "
            + "(id BIGINT PRIMARY KEY,middle_id BIGINT,value BIGINT,"
            + "shared BIGINT,label VARCHAR(32))");
    execute(
        session,
        result,
        "CREATE TABLE join_scope_a "
            + "(id BIGINT PRIMARY KEY,a_only BIGINT)");
    execute(
        session,
        result,
        "CREATE TABLE join_scope_b "
            + "(id BIGINT PRIMARY KEY,b_only BIGINT)");
    execute(
        session,
        result,
        "CREATE TABLE join_scope_c "
            + "(id BIGINT PRIMARY KEY,c_only BIGINT)");
    execute(
        session,
        result,
        "INSERT INTO outer_scope VALUES "
            + "(1,100,70,5,'猫😀',95),(2,200,80,7,'éclair',150)");
    execute(
        session,
        result,
        "INSERT INTO typed_scope VALUES "
            + "(1,1.20,TRUE,'猫😀',DATE '2024-01-01',TIME '12:00:00',"
            + "TIMESTAMP '2024-01-01 12:00:00',"
            + "TIMESTAMP WITH TIME ZONE '2024-01-01 12:00:00+00:00')");
    execute(
        session,
        result,
        "INSERT INTO middle_scope VALUES (10,1,700,95),(20,2,800,999)");
    execute(
        session,
        result,
        "INSERT INTO inner_scope VALUES "
            + "(1,10,95,7,'猫😀'),(2,20,150,8,'éclair')");
    execute(session, result, "INSERT INTO join_scope_a VALUES (1,100),(2,200)");
    execute(session, result, "INSERT INTO join_scope_b VALUES (1,95),(2,150)");
    execute(session, result, "INSERT INTO join_scope_c VALUES (1,95),(2,150)");
  }

  private static void execute(
      SqlSession session, SqlExecutionResult result, String sql) {
    assertEquals(StatusCode.OK, session.execute(sql, result), sql);
  }

  private static void assertExplain(
      SqlSession session, String sql, StatusCode expected) {
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();
    assertEquals(
        expected == StatusCode.FEATURE_NOT_SUPPORTED ? expected : StatusCode.OK,
        new SqlParser().parseQuery(sql, query, command),
        "parser: " + sql);
    SqlScanCursor cursor = new SqlScanCursor();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(expected, session.beginScan(sql, cursor), sql);
    if (expected.isOk()) {
      assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
    } else {
      assertEquals(StatusCode.OK, cursor.reset(), sql);
    }
  }

  private static void assertRows(SqlSession session, String sql, long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
      assertEquals(value, row.valueAt(0), sql);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
  }

  private static final class Fixture {
    private final RelationalDatabase database;
    private final SqlSession session;
    private final SqlExecutionResult result = new SqlExecutionResult();

    private Fixture(RelationalDatabase relationalDatabase, SqlSession sqlSession) {
      database = relationalDatabase;
      session = sqlSession;
    }

    private void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
