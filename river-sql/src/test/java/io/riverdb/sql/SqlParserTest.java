package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlParserTest {
  private static volatile long allocationGuard;
  @Test
  void parsesExecutablePointStatementSubset() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse("create table accounts;", command));
    assertEquals(SqlCommandType.CREATE_TABLE, command.type());
    assertName("accounts", command.tableName());
    assertName("key", command.firstColumnName());
    assertName("value", command.secondColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE balances (account_id BIGINT PRIMARY KEY, amount BIGINT)",
            command));
    assertName("account_id", command.firstColumnName());
    assertName("amount", command.secondColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE ledger "
                + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)",
            command));
    assertEquals(3, command.columnCount());
    assertName("region", command.columnName(2));
    assertEquals(
        StatusCode.OK,
        parser.parse("CREATE UNIQUE INDEX accounts_value ON accounts(value)", command));
    assertEquals(SqlCommandType.CREATE_UNIQUE_INDEX, command.type());
    assertName("accounts_value", command.indexName());
    assertName("accounts", command.tableName());
    assertName("value", command.firstColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id, NULL FROM accounts WHERE id=1", command));
    assertEquals(2, command.columnCount());
    assertFalse(command.isNullProjection(0));
    assertTrue(command.isNullProjection(1));
    assertName("null", command.columnName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse("CREATE INDEX accounts_region ON accounts(region)", command));
    assertEquals(SqlCommandType.CREATE_INDEX, command.type());
    assertName("accounts_region", command.indexName());
    assertName("accounts", command.tableName());
    assertName("region", command.firstColumnName());
    assertEquals(StatusCode.OK, parser.parse("INSERT INTO accounts VALUES (7, -9)", command));
    assertEquals(SqlCommandType.INSERT, command.type());
    assertEquals(7, command.key());
    assertEquals(-9, command.value());
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO accounts VALUES (1, 10), (2, 20), (3, 30)", command));
    assertEquals(3, command.insertRowCount());
    assertEquals(1, command.insertKey(0));
    assertEquals(20, command.insertValue(1));
    assertEquals(3, command.insertKey(2));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO ledger VALUES (1, 100, 7), (2, 200, 8)", command));
    assertEquals(2, command.insertRowCount());
    assertEquals(3, command.insertColumnCount());
    assertEquals(8, command.insertValue(1, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO ledger VALUES (1, NULL, 7), (2, 20, NULL)",
            command));
    assertFalse(command.insertIsNull(0, 0));
    assertTrue(command.insertIsNull(0, 1));
    assertTrue(command.insertIsNull(1, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO ledger (region, id, balance) VALUES (7, 1, 100)",
            command));
    assertEquals(3, command.columnCount());
    assertName("region", command.columnName(0));
    assertName("balance", command.columnName(2));
    assertEquals(7, command.insertValue(0, 0));
    assertEquals(StatusCode.OK, parser.parse("select value from accounts where key=7", command));
    assertEquals(SqlCommandType.SELECT, command.type());
    assertName("value", command.firstColumnName());
    assertName("key", command.predicateColumnName());
    assertEquals(7, command.key());
    assertEquals(StatusCode.OK, parser.parse("SELECT COUNT(*) FROM accounts", command));
    assertEquals(SqlCommandType.COUNT, command.type());
    assertName("accounts", command.tableName());
    assertEquals(false, command.hasPredicate());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT COUNT(*) FROM accounts WHERE region=7", command));
    assertEquals(SqlCommandType.COUNT, command.type());
    assertEquals(true, command.hasPredicate());
    assertEquals(true, command.isEqualityPredicate());
    assertName("region", command.predicateColumnName());
    assertEquals(7, command.key());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(*) FROM accounts WHERE region >= -2 AND region < 9",
            command));
    assertEquals(true, command.hasPredicate());
    assertEquals(false, command.isEqualityPredicate());
    assertEquals(-2, command.scanLowerInclusive());
    assertEquals(9, command.scanUpperExclusive());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts "
                + "WHERE value >= 100 AND value < 300 AND region=7 "
                + "GROUP BY region ORDER BY region ASC",
            command));
    assertEquals(SqlCommandType.GROUP_COUNT, command.type());
    assertName("region", command.firstColumnName());
    assertName("accounts", command.tableName());
    assertEquals(2, command.predicateCount());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts GROUP BY value",
            command));
    assertEquals(StatusCode.OK, parser.parse("SELECT key, value FROM accounts", command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertName("accounts", command.tableName());
    assertEquals(false, command.isBoundedScan());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts ORDER BY value ASC LIMIT 7",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(true, command.isOrdered());
    assertEquals(false, command.isDescendingOrder());
    assertName("value", command.orderColumnName());
    assertEquals(7, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key, regions.code FROM accounts "
                + "JOIN regions ON accounts.region=regions.id "
                + "WHERE accounts.region >= 7 AND accounts.region < 9 LIMIT 0",
            command));
    assertEquals(0, command.rowLimit());
    assertName("accounts", command.predicateTableName());
    assertName("region", command.predicateColumnName());
    assertEquals(7, command.scanLowerInclusive());
    assertEquals(9, command.scanUpperExclusive());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT key FROM accounts LIMIT -1", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DISTINCT region FROM accounts WHERE value=100 AND region=7 "
                + "ORDER BY region LIMIT 2",
            command));
    assertEquals(SqlCommandType.DISTINCT_SCAN, command.type());
    assertName("region", command.firstColumnName());
    assertEquals(2, command.predicateCount());
    assertEquals(2, command.rowLimit());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT DISTINCT region, key FROM accounts", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT key FROM accounts ORDER BY key DESC", command));
    assertTrue(command.isDescendingOrder());
    assertName("key", command.orderColumnName());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts "
                + "GROUP BY region ORDER BY region DESC",
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "SELECT DISTINCT region FROM accounts ORDER BY region DESC",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key, regions.code FROM accounts "
                + "JOIN regions ON accounts.region=regions.id",
            command));
    assertEquals(SqlCommandType.JOIN_SCAN, command.type());
    assertName("accounts", command.tableName());
    assertName("regions", command.joinTableName());
    assertName("region", command.joinOuterColumnName());
    assertName("id", command.joinInnerColumnName());
    assertName("accounts", command.columnTableName(0));
    assertName("key", command.columnName(0));
    assertName("regions", command.columnTableName(1));
    assertName("code", command.columnName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key, r.code FROM accounts a "
                + "JOIN regions AS r ON a.region=r.id "
                + "WHERE a.region=7 AND r.code>=7000",
            command));
    assertName("a", command.tableAlias());
    assertName("r", command.joinTableAlias());
    assertName("a", command.columnTableName(0));
    assertName("r", command.columnTableName(1));
    assertName("a", command.predicateTableName(0));
    assertName("r", command.predicateTableName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT region, key, value FROM accounts WHERE key=7", command));
    assertEquals(SqlCommandType.SELECT, command.type());
    assertEquals(3, command.columnCount());
    assertName("region", command.columnName(0));
    assertName("value", command.columnName(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE key >= 11 AND key < 29",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(true, command.isBoundedScan());
    assertEquals(11, command.scanLowerInclusive());
    assertEquals(29, command.scanUpperExclusive());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE value = 701",
            command));
    assertEquals(SqlCommandType.SELECT, command.type());
    assertEquals(701, command.key());
    assertName("key", command.firstColumnName());
    assertName("value", command.secondColumnName());
    assertName("value", command.predicateColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE value >= -50 AND value < 75",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(-50, command.scanLowerInclusive());
    assertEquals(75, command.scanUpperExclusive());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key FROM accounts WHERE region=7 "
                + "AND value >= 100 AND value < 300 AND key=2",
            command));
    assertEquals(3, command.predicateCount());
    assertName("region", command.predicateColumnName(0));
    assertEquals(7, command.predicateValue(0));
    assertName("value", command.predicateColumnName(1));
    assertEquals(100, command.predicateLowerInclusive(1));
    assertEquals(300, command.predicateUpperExclusive(1));
    assertName("key", command.predicateColumnName(2));
    assertEquals(2, command.predicateValue(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key FROM accounts "
                + "JOIN regions ON accounts.region=regions.id "
                + "WHERE accounts.region=7 AND accounts.value >= 100 "
                + "AND accounts.value < 300 AND regions.code=7000",
            command));
    assertEquals(3, command.predicateCount());
    assertName("accounts", command.predicateTableName(1));
    assertName("value", command.predicateColumnName(1));
    assertName("regions", command.predicateTableName(2));
    assertName("code", command.predicateColumnName(2));
    assertEquals(StatusCode.OK, parser.parse("UPDATE accounts SET value=11 WHERE key=7", command));
    assertEquals(SqlCommandType.UPDATE, command.type());
    assertEquals(11, command.value());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=12, region=-3 WHERE key=7",
            command));
    assertEquals(2, command.updateColumnCount());
    assertName("balance", command.columnName(0));
    assertName("region", command.columnName(1));
    assertEquals(12, command.updateValue(0));
    assertEquals(-3, command.updateValue(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=NULL, region=4 WHERE key=7",
            command));
    assertTrue(command.updateIsNull(0));
    assertFalse(command.updateIsNull(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET region=9 WHERE balance >= 100 AND balance < 500",
            command));
    assertEquals(false, command.isEqualityPredicate());
    assertEquals(100, command.scanLowerInclusive());
    assertEquals(500, command.scanUpperExclusive());
    assertEquals(StatusCode.OK, parser.parse("DELETE FROM accounts WHERE key = 7", command));
    assertEquals(SqlCommandType.DELETE, command.type());
    assertEquals(true, command.isEqualityPredicate());
    assertEquals(
        StatusCode.OK,
        parser.parse("DELETE FROM accounts WHERE key >= 10 AND key < 20", command));
    assertEquals(false, command.isEqualityPredicate());
    assertEquals(10, command.scanLowerInclusive());
    assertEquals(20, command.scanUpperExclusive());
    assertEquals(StatusCode.OK, parser.parse("BEGIN;", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(false, command.isReadCommittedTransaction());
    assertEquals(false, command.isSerializableTransaction());
    assertEquals(StatusCode.OK, parser.parse("BEGIN READ COMMITTED", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(true, command.isReadCommittedTransaction());
    assertEquals(false, command.isSerializableTransaction());
    assertEquals(StatusCode.OK, parser.parse("BEGIN REPEATABLE READ", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(false, command.isReadCommittedTransaction());
    assertEquals(false, command.isSerializableTransaction());
    assertEquals(StatusCode.OK, parser.parse("BEGIN SERIALIZABLE", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(false, command.isReadCommittedTransaction());
    assertEquals(true, command.isSerializableTransaction());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("BEGIN READ", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("BEGIN REPEATABLE", command));
    assertEquals(StatusCode.OK, parser.parse("SAVEPOINT before_update", command));
    assertEquals(SqlCommandType.SAVEPOINT, command.type());
    assertName("before_update", command.savepointName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ROLLBACK TO SAVEPOINT before_update", command));
    assertEquals(SqlCommandType.ROLLBACK_TO_SAVEPOINT, command.type());
    assertName("before_update", command.savepointName());
    assertEquals(
        StatusCode.OK,
        parser.parse("RELEASE SAVEPOINT before_update", command));
    assertEquals(SqlCommandType.RELEASE_SAVEPOINT, command.type());
    assertEquals(StatusCode.OK, parser.parse("COMMIT", command));
    assertEquals(SqlCommandType.COMMIT, command.type());
    assertEquals(StatusCode.OK, parser.parse("ROLLBACK", command));
    assertEquals(SqlCommandType.ROLLBACK, command.type());
    assertEquals(StatusCode.OK, parser.parse("CHECKPOINT", command));
    assertEquals(SqlCommandType.CHECKPOINT, command.type());
  }

  @Test
  void rejectsMalformedUnsupportedAndOverflowInput() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse("SELECT * FROM x", command));
    assertTrue(command.isSelectAll());
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("CREATE TABLE bad-name", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE TABLE only_key (id BIGINT PRIMARY KEY)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("INSERT INTO x VALUES (9223372036854775808, 1)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO x VALUES (0, -9223372036854775808)", command));
    assertEquals(Long.MIN_VALUE, command.value());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("DROP TABLE x", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT key, value FROM x WHERE key ! 1", command));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        parser.parse(
            "SELECT key FROM x WHERE a=1 AND b=2 AND c=3 AND d=4 "
                + "AND e=5 AND f=6 AND g=7 AND h=8 AND i=9",
            command));
    StringBuilder tooManyRows = new StringBuilder("INSERT INTO x VALUES ");
    for (int index = 0; index <= SqlCommand.MAXIMUM_INSERT_ROWS; index++) {
      if (index > 0) {
        tooManyRows.append(',');
      }
      tooManyRows.append('(').append(index).append(',').append(index).append(')');
    }
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        parser.parse(tooManyRows.toString(), command));
    assertFalse(command.isAvailable());
  }

  @Test
  void parsesBigintComparisonsWithoutLosingHalfOpenRanges() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value=-4", command));
    assertEquals(SqlComparison.EQUAL, command.comparison(0));
    assertEquals(-4, command.predicateValue(0));

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value<>0", command));
    assertEquals(SqlComparison.NOT_EQUAL, command.comparison(0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value!=0", command));
    assertEquals(SqlComparison.NOT_EQUAL, command.comparison(0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value<-9223372036854775807",
            command));
    assertEquals(SqlComparison.LESS_THAN, command.comparison(0));
    assertEquals(-9223372036854775807L, command.predicateValue(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value<=-9223372036854775808",
            command));
    assertEquals(SqlComparison.LESS_OR_EQUAL, command.comparison(0));
    assertEquals(Long.MIN_VALUE, command.predicateValue(0));

    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value>9223372036854775806", command));
    assertEquals(SqlComparison.GREATER_THAN, command.comparison(0));
    assertEquals(9223372036854775806L, command.predicateValue(0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM metrics WHERE value>=9223372036854775807", command));
    assertEquals(SqlComparison.GREATER_OR_EQUAL, command.comparison(0));
    assertEquals(Long.MAX_VALUE, command.predicateValue(0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value>=-5 AND value<8",
            command));
    assertEquals(1, command.predicateCount());
    assertEquals(SqlComparison.HALF_OPEN_RANGE, command.comparison(0));
    assertEquals(-5, command.predicateLowerInclusive(0));
    assertEquals(8, command.predicateUpperExclusive(0));

    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value>=-5 AND value<=8",
            command));
    assertEquals(2, command.predicateCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, command.comparison(0));
    assertEquals(SqlComparison.LESS_OR_EQUAL, command.comparison(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM metrics WHERE value>=-5 AND id<8",
            command));
    assertEquals(2, command.predicateCount());
    assertName("value", command.predicateColumnName(0));
    assertName("id", command.predicateColumnName(1));
  }

  @Test
  void parsesAndCompilesBoundedDerivedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.id FROM "
                + "(SELECT id, region FROM accounts WHERE accounts.region=7) d "
                + "WHERE d.id >= 1 AND d.id < 5 ORDER BY region DESC LIMIT 2",
            query,
            command));
    assertEquals(2, query.blockCount());
    assertName("accounts", command.tableName());
    assertName("id", command.firstColumnName());
    assertEquals(2, command.predicateCount());
    assertName("region", command.predicateColumnName(0));
    assertEquals(7, command.predicateValue(0));
    assertName("id", command.predicateColumnName(1));
    assertEquals(1, command.predicateLowerInclusive(1));
    assertEquals(5, command.predicateUpperExclusive(1));
    assertName("region", command.orderColumnName());
    assertTrue(command.isDescendingOrder());
    assertEquals(2, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT d.account_id AS result_id FROM "
                + "(SELECT id AS account_id, balance funds FROM accounts "
                + "WHERE balance >= 100 AND balance < 300) d "
                + "WHERE d.account_id=2 ORDER BY result_id",
            query,
            command));
    assertName("id", command.firstColumnName());
    assertName("result_id", command.columnOutputName(0));
    assertName("balance", command.predicateColumnName(0));
    assertName("id", command.predicateColumnName(1));
    assertName("id", command.orderColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT second.final_id FROM "
                + "(SELECT first.account_id AS final_id FROM "
                + "(SELECT id AS account_id FROM accounts) first) second",
            query,
            command));
    assertName("id", command.firstColumnName());
    assertName("final_id", command.columnOutputName(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedQuery(33), query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.id FROM (SELECT id FROM accounts LIMIT 1) d",
            query,
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT wrong.id FROM (SELECT id FROM accounts) d",
            query,
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.region FROM (SELECT id FROM accounts) d",
            query,
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.id FROM (SELECT other.id FROM accounts) d",
            query,
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT d.duplicate FROM "
                + "(SELECT id AS duplicate, region AS duplicate FROM accounts) d",
            query,
            command));
  }

  @Test
  void parsesScalarPredicatesAsBoundedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id, balance FROM accounts WHERE region=7 AND balance = "
                + "(SELECT balance FROM lookup WHERE lookup.id=7)",
            query,
            command));
    assertEquals(2, query.blockCount());
    assertTrue(query.hasScalarPredicate());
    assertEquals(1, query.scalarPredicate());
    assertName("accounts", command.tableName());
    assertName("region", command.predicateColumnName(0));
    assertEquals(7, command.predicateValue(0));
    assertName("balance", command.predicateColumnName(1));
    assertEquals(0, command.predicateValue(1));
    SqlCommand scalar = query.scalarCommand();
    assertName("lookup", scalar.tableName());
    assertName("balance", scalar.firstColumnName());
    assertName("id", scalar.predicateColumnName());
    assertEquals(7, scalar.predicateValue(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE region="
                + "(SELECT id FROM regions "
                + "WHERE regions.id=accounts.region)",
            query,
            command));
    scalar = query.scalarCommand();
    assertTrue(scalar.isColumnPredicate(0));
    assertName("regions", scalar.predicateTableName(0));
    assertName("id", scalar.predicateColumnName(0));
    assertName("accounts", scalar.predicateValueTableName(0));
    assertName("region", scalar.predicateValueColumnName(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedScalarQuery(3), query, command));
    assertEquals(3, query.blockCount());
    assertEquals(0, query.scalarPredicate(0));
    assertEquals(0, query.scalarPredicate(1));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedScalarQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedScalarQuery(33), query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE balance = "
                + "(SELECT id, balance FROM lookup WHERE id=7)",
            query,
            command));
  }

  @Test
  void parsesExistencePredicatesAsBoundedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id FROM lookup WHERE lookup.region=7) ORDER BY id",
            query,
            command));
    assertEquals(2, query.blockCount());
    assertTrue(query.hasExistencePredicate());
    assertFalse(query.existenceNegated());
    assertName("accounts", command.tableName());
    assertName("id", command.orderColumnName());
    assertName("lookup", query.existenceCommand().tableName());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE NOT EXISTS "
                + "(SELECT id FROM lookup WHERE lookup.region=7)",
            query,
            command));
    assertTrue(query.existenceNegated());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id FROM regions "
                + "WHERE regions.id=accounts.region)",
            query,
            command));
    SqlCommand correlated = query.existenceCommand();
    assertTrue(correlated.isColumnPredicate(0));
    assertName("regions", correlated.predicateTableName(0));
    assertName("id", correlated.predicateColumnName(0));
    assertName("accounts", correlated.predicateValueTableName(0));
    assertName("region", correlated.predicateValueColumnName(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT a.id FROM accounts AS a WHERE EXISTS "
                + "(SELECT b.id FROM accounts b "
                + "WHERE b.region=a.region AND b.id=3)",
            query,
            command));
    assertName("accounts", command.tableName());
    assertName("a", command.tableAlias());
    correlated = query.existenceCommand();
    assertName("accounts", correlated.tableName());
    assertName("b", correlated.tableAlias());
    assertName("b", correlated.predicateTableName(0));
    assertName("a", correlated.predicateValueTableName(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedExistenceQuery(3), query, command));
    assertEquals(3, query.blockCount());
    assertFalse(query.existenceNegated(0));
    assertFalse(query.existenceNegated(1));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedExistenceQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedExistenceQuery(33), query, command));
  }

  @Test
  void parsesMembershipPredicatesAsBoundedQueryBlocks() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE region=7 AND balance IN "
                + "(SELECT balance FROM lookup WHERE lookup.id=9)",
            query,
            command));
    assertTrue(query.hasMembershipPredicate());
    assertFalse(query.membershipNegated());
    assertEquals(1, query.membershipPredicate());
    assertName("balance", command.predicateColumnName(1));
    assertName("lookup", query.membershipCommand().tableName());
    assertName("balance", query.membershipCommand().firstColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE id NOT IN "
                + "(SELECT NULL FROM lookup WHERE id=9)",
            query,
            command));
    assertTrue(query.membershipNegated());
    assertTrue(query.membershipCommand().isNullProjection(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE region NOT IN "
                + "(SELECT id FROM regions "
                + "WHERE regions.id=accounts.region)",
            query,
            command));
    SqlCommand correlated = query.membershipCommand();
    assertTrue(query.membershipNegated());
    assertTrue(correlated.isColumnPredicate(0));
    assertName("regions", correlated.predicateTableName(0));
    assertName("id", correlated.predicateColumnName(0));
    assertName("accounts", correlated.predicateValueTableName(0));
    assertName("region", correlated.predicateValueColumnName(0));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedMembershipQuery(3), query, command));
    assertEquals(3, query.blockCount());
    assertEquals(0, query.membershipPredicate(0));
    assertEquals(0, query.membershipPredicate(1));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(nestedMembershipQuery(32), query, command));
    assertEquals(32, query.blockCount());
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        parser.parseQuery(nestedMembershipQuery(33), query, command));
  }

  @Test
  void parsesMixedNestedPredicateForms() {
    SqlParser parser = new SqlParser();
    SqlQuery query = new SqlQuery();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT id FROM accounts WHERE EXISTS "
                + "(SELECT id FROM accounts WHERE id IN "
                + "(SELECT id FROM accounts WHERE id="
                + "(SELECT id FROM accounts WHERE id=1)))",
            query,
            command));
    assertEquals(4, query.blockCount());
    assertTrue(query.hasExistencePredicate(0));
    assertTrue(query.hasMembershipPredicate(1));
    assertTrue(query.hasScalarPredicate(2));
    assertFalse(query.hasScalarPredicate(0));
    assertFalse(query.hasExistencePredicate(1));
    assertFalse(query.hasMembershipPredicate(2));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery(
            "SELECT a.id FROM accounts a WHERE EXISTS "
                + "(SELECT b.id FROM accounts b WHERE b.id IN "
                + "(SELECT c.id FROM accounts c WHERE c.id=a.id))",
            query,
            command));
    assertEquals(3, query.blockCount());
    assertName("a", command.tableAlias());
    assertName("b", query.block(1).tableAlias());
    assertName("c", query.block(2).tableAlias());
    assertTrue(query.block(2).isColumnPredicate(0));
    assertName("a", query.block(2).predicateValueTableName(0));
    assertName("id", query.block(2).predicateValueColumnName(0));
  }

  @Test
  void parsesNullPredicates() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM nullable_values WHERE value IS NULL",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(1, command.predicateCount());
    assertTrue(command.isNullPredicate(0));
    assertFalse(command.isNullPredicateNegated(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM nullable_values "
                + "WHERE value IS NOT NULL AND rank IS NULL",
            command));
    assertEquals(2, command.predicateCount());
    assertTrue(command.isNullPredicate(0));
    assertTrue(command.isNullPredicateNegated(0));
    assertTrue(command.isNullPredicate(1));
    assertFalse(command.isNullPredicateNegated(1));
  }

  @Test
  void warmedParseReusesCommandAndParserState() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();
    for (int index = 0; index < 1_000; index++) {
      allocationGuard += parser.parseQuery(
          "SELECT d.key FROM "
              + "(SELECT key, region FROM accounts WHERE accounts.region=3) d "
              + "WHERE d.key=7",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value="
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE EXISTS "
              + "(SELECT key FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value NOT IN "
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM nullable_values WHERE value IS NOT NULL",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region NOT IN "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT a.id FROM accounts AS a WHERE EXISTS "
              + "(SELECT b.id FROM accounts b WHERE b.region=a.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id=(SELECT id FROM accounts "
              + "WHERE id=(SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE EXISTS (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id IN (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id="
              + "(SELECT id FROM accounts WHERE id=1)))",
          query,
          command).ordinal();
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 1_000; index++) {
      allocationGuard += parser.parseQuery(
          "SELECT d.key FROM "
              + "(SELECT key, region FROM accounts WHERE accounts.region=3) d "
              + "WHERE d.key=7",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE EXISTS "
              + "(SELECT key FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value="
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT key FROM accounts WHERE value NOT IN "
              + "(SELECT value FROM lookup WHERE lookup.key=7)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM nullable_values WHERE value IS NOT NULL",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE region NOT IN "
              + "(SELECT id FROM regions WHERE regions.id=accounts.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT a.id FROM accounts AS a WHERE EXISTS "
              + "(SELECT b.id FROM accounts b WHERE b.region=a.region)",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id=(SELECT id FROM accounts "
              + "WHERE id=(SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE EXISTS (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE id IN (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id=1))",
          query,
          command).ordinal();
      allocationGuard += parser.parseQuery(
          "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM accounts "
              + "WHERE id IN (SELECT id FROM accounts WHERE id="
              + "(SELECT id FROM accounts WHERE id=1)))",
          query,
          command).ordinal();
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 256, "warmed SQL parse allocated bytes: " + allocated);
  }

  private static String nestedQuery(int blocks) {
    String query = "SELECT id FROM accounts";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT d" + depth + ".id FROM (" + query + ") d" + depth;
    }
    return query;
  }

  private static String nestedScalarQuery(int blocks) {
    String query = "SELECT id FROM accounts WHERE id=1";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT id FROM accounts WHERE id=(" + query + ")";
    }
    return query;
  }

  private static String nestedExistenceQuery(int blocks) {
    String query = "SELECT id FROM accounts WHERE id=1";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT id FROM accounts WHERE EXISTS (" + query + ")";
    }
    return query;
  }

  private static String nestedMembershipQuery(int blocks) {
    String query = "SELECT id FROM accounts WHERE id=1";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT id FROM accounts WHERE id IN (" + query + ")";
    }
    return query;
  }

  private static void assertName(String expected, SqlIdentifier actual) {
    assertEquals(expected.length(), actual.length());
    for (int index = 0; index < expected.length(); index++) {
      assertEquals(expected.charAt(index), actual.charAt(index));
    }
  }
}
