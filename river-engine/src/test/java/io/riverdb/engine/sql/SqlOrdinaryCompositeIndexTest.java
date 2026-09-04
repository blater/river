package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlOrdinaryCompositeIndexTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4f5244494e415259L, 0x434f4d504f534954L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void ordinaryPrimaryTableUsesCompositeTypedTupleIndexes(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = sqlSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE accounts (id BIGINT PRIMARY KEY,tenant INTEGER,"
            + "amount NUMERIC(12,2),ratio DOUBLE PRECISION,label VARCHAR(16),day DATE)",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX accounts_typed "
            + "ON accounts(tenant,amount,ratio,label,day)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO accounts VALUES "
            + "(1,7,12.50,1.25,'north',DATE '2026-08-27')", result));
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO accounts VALUES "
            + "(2,7,12.50,1.25,'north',DATE '2026-08-27')", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO accounts VALUES "
            + "(2,7,12.50,1.50,'north',DATE '2026-08-27')", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void onePartTableUniqueAndForeignConstraintsAreEnforced(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = sqlSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE parents (id BIGINT PRIMARY KEY,code INTEGER,UNIQUE(code))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE children (id BIGINT PRIMARY KEY,parent_code INTEGER,"
            + "FOREIGN KEY(parent_code) REFERENCES parents(code))", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO parents VALUES (1,17)", result));
    assertEquals(StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO parents VALUES (2,17)", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO children VALUES (1,17)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("INSERT INTO children VALUES (2,18)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void compositeForeignKeyBlocksParentDropUntilChildIsDropped(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = sqlSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE parents (tenant INTEGER,code NUMERIC(12,2),"
            + "PRIMARY KEY(tenant,code))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE children (id BIGINT PRIMARY KEY,tenant INTEGER,"
            + "parent_code NUMERIC(12,2),FOREIGN KEY(tenant,parent_code) "
            + "REFERENCES parents(tenant,code))", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DROP TABLE parents", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT keep_child", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE children", result));
    assertEquals(StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT keep_child", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DROP TABLE parents", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE children", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE parents", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void descriptorTableWithLiteralDefaultSupportsCompositeIndex(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = sqlSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE settings "
            + "(id BIGINT PRIMARY KEY,first_value BIGINT DEFAULT 0,second_value BIGINT)",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX settings_pair ON settings(first_value,second_value)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void pointSelectProvesSingletonIndependentlyOfChosenCompositeIndex(
      @TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = sqlSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE singleton_choice (id BIGINT PRIMARY KEY,a INTEGER,b INTEGER,"
            + "c INTEGER,payload BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX singleton_alternate ON singleton_choice(a,b)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX singleton_more_parts ON singleton_choice(a,b,c)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO singleton_choice VALUES (1,1,2,3,91),(2,4,5,6,92)", result));

    assertEquals(StatusCode.OK, session.execute(
        "SELECT payload FROM singleton_choice "
            + "WHERE id=1 AND a=1 AND b=2 AND c=3", result));
    assertEquals(91, result.value());
    assertEquals(StatusCode.OK, session.execute(
        "SELECT payload FROM singleton_choice WHERE a=4 AND b=5 AND c=6", result));
    assertEquals(92, result.value());

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void publicScalarRowKeyIsOnlyAOnePartBigintPrimaryKey(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = sqlSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE scalar_key (payload BIGINT,id BIGINT PRIMARY KEY)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO scalar_key VALUES (91,7)", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT payload FROM scalar_key WHERE id=7", result));
    assertEquals(7, result.key());
    assertJoinKey(session,
        "SELECT a.payload FROM scalar_key a JOIN scalar_key b ON a.id=b.id", 7);
    assertJoinKey(session,
        "SELECT a.payload AS selected_payload FROM scalar_key a JOIN scalar_key b "
            + "ON a.id=b.id ORDER BY selected_payload DESC", 7);

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE composite_key (payload BIGINT,tenant INTEGER,code INTEGER,"
            + "PRIMARY KEY(tenant,code))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO composite_key VALUES (92,3,4)", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT payload FROM composite_key WHERE payload=92", result));
    assertEquals(0, result.key());
    assertJoinKey(session,
        "SELECT a.payload FROM composite_key a JOIN composite_key b "
            + "ON a.tenant=b.tenant AND a.code=b.code", 0);
    assertJoinKey(session,
        "SELECT a.payload AS selected_payload FROM composite_key a JOIN composite_key b "
            + "ON a.tenant=b.tenant AND a.code=b.code "
            + "ORDER BY selected_payload DESC", 0);
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT payload FROM composite_key ORDER BY payload", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(0, row.key());
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE keyless_rows (payload BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO keyless_rows VALUES (93)", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT payload FROM keyless_rows WHERE payload=93", result));
    assertEquals(0, result.key());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertJoinKey(SqlSession session, String sql, long expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(expected, row.key());
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  @Test
  void nullableUniqueParentKeyIsNotReferencedByNullChildKey(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = sqlSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE nullable_parents (id BIGINT PRIMARY KEY,code BIGINT UNIQUE)",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE nullable_children (id BIGINT PRIMARY KEY,parent_code BIGINT,"
            + "FOREIGN KEY(parent_code) REFERENCES nullable_parents(code))",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO nullable_parents VALUES (1,NULL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO nullable_children VALUES (1,NULL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM nullable_parents WHERE id=1", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void tableNamesCannotCrossLegacyAndDescriptorCatalogs(@TempDir Path root) {
    RelationalDatabase database = create(root);
    RelationalSession session = relationalSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    TableSchema legacySchema = new TableSchema();
    assertEquals(StatusCode.OK, legacySchema.addBigint("id"));
    assertEquals(StatusCode.OK, legacySchema.addBigint("value"));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.createTable("legacy_first", legacySchema, new TableDefinition()));
    assertEquals(StatusCode.CONFLICT,
        prepareDescriptor(session, "legacy_first"));
    assertEquals(StatusCode.OK, session.abort(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, prepareDescriptor(session, "descriptor_first"));
    assertEquals(StatusCode.CONFLICT,
        session.createTable("descriptor_first", legacySchema, new TableDefinition()));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  private static StatusCode prepareDescriptor(RelationalSession session, String name) {
    SqlCommand command = new SqlCommand();
    SqlParser parser = new SqlParser();
    StatusCode status = parser.parse(
        "CREATE TABLE " + name + " (id BIGINT PRIMARY KEY,value INTEGER)", command);
    if (!status.isOk()) return status;
    SqlDescriptorTableBuilder builder = new SqlDescriptorTableBuilder();
    StatusDetail detail = new StatusDetail(128);
    status = builder.build(command, detail);
    return status.isOk()
        ? session.prepareDescriptorTable(name, builder.descriptor(), detail) : status;
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    return opened.database();
  }

  private static SqlSession sqlSession(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }

  private static RelationalSession relationalSession(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }
}
