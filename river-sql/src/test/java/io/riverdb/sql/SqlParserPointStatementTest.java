package io.riverdb.sql;

import static io.riverdb.sql.SqlParserPredicateTestSupport.assertMutationPostfix;
import static io.riverdb.sql.SqlParserParameterTextSupport.assertName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.assertPostfix;
import static io.riverdb.sql.SqlParserParameterTextSupport.assertText;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingComparison;
import static io.riverdb.sql.SqlParserPredicateTestSupport.havingValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipCount;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipHasNull;
import static io.riverdb.sql.SqlParserPredicateTestSupport.membershipValue;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateColumnName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateComparison;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateDescriptor;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateLower;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateTableName;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateUpper;
import static io.riverdb.sql.SqlParserPredicateTestSupport.predicateValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlParserPointStatementTest {
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
    assertTrue(command.columnIsNotNull(0));
    assertFalse(command.columnIsNotNull(1));
    assertFalse(command.columnIsNotNull(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE required_values "
                + "(id BIGINT NOT NULL PRIMARY KEY, value BIGINT NOT NULL, "
                + "note BIGINT)",
            command));
    assertTrue(command.columnIsNotNull(0));
    assertTrue(command.columnIsNotNull(1));
    assertFalse(command.columnIsNotNull(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE default_values "
                + "(id BIGINT PRIMARY KEY, required BIGINT NOT NULL DEFAULT -7, "
                + "optional BIGINT DEFAULT 9 NOT NULL, note BIGINT DEFAULT 0)",
            command));
    assertFalse(command.columnHasDefault(0));
    assertTrue(command.columnHasDefault(1));
    assertEquals(-7, command.columnDefaultValue(1));
    assertTrue(command.columnIsNotNull(1));
    assertTrue(command.columnHasDefault(2));
    assertEquals(9, command.columnDefaultValue(2));
    assertTrue(command.columnIsNotNull(2));
    assertTrue(command.columnHasDefault(3));
    assertEquals(0, command.columnDefaultValue(3));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, code VARCHAR(7) NOT NULL DEFAULT 'new', "
                + "note VARCHAR(7))",
            command));
    assertTrue(command.columnIsVarchar(1));
    assertTrue(command.columnIsNotNull(1));
    assertText("new", command, command.columnDefaultValue(1));
    assertTrue(command.columnIsVarchar(2));
    assertEquals(
        StatusCode.OK,
        parser.parse("CREATE UNIQUE INDEX accounts_value ON accounts(value)", command));
    assertEquals(SqlCommandType.CREATE_UNIQUE_INDEX, command.type());
    assertName("accounts_value", command.indexName());
    assertName("accounts", command.tableName());
    assertName("value", command.firstColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM labels WHERE code >= 'alpha' AND code < 'omega'",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlTypeDescriptor.varchar(5), predicateDescriptor(command, 0));
    assertEquals(SqlTypeDescriptor.varchar(5), predicateDescriptor(command, 1));
    assertText("alpha", command, predicateValue(command, 0));
    assertText("omega", command, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM labels WHERE code IN ('beta', 'alpha')", command));
    assertEquals(
        SqlTypeDescriptor.varchar(4),
        command.wherePredicates().memberDescriptor(0, 0));
    assertEquals(
        SqlTypeDescriptor.varchar(5),
        command.wherePredicates().memberDescriptor(0, 1));
    assertText("beta", command, membershipValue(command, 0, 0));
    assertText("alpha", command, membershipValue(command, 0, 1));
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
    assertCompositeIndexColumns(parser, command);
    assertEquals(
        StatusCode.OK,
        parser.parse("DROP INDEX accounts_region ON accounts", command));
    assertEquals(SqlCommandType.DROP_INDEX, command.type());
    assertName("accounts_region", command.indexName());
    assertName("accounts", command.tableName());
    assertEquals(StatusCode.OK, parser.parse("DROP TABLE accounts", command));
    assertEquals(SqlCommandType.DROP_TABLE, command.type());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ALTER TABLE accounts RENAME TO customers", command));
    assertEquals(SqlCommandType.ALTER_TABLE_RENAME, command.type());
    assertName("accounts", command.tableName());
    assertName("customers", command.renamedTableName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ALTER TABLE customers RENAME COLUMN region TO area", command));
    assertEquals(SqlCommandType.ALTER_TABLE_RENAME_COLUMN, command.type());
    assertName("customers", command.tableName());
    assertName("region", command.firstColumnName());
    assertName("area", command.secondColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse("ALTER INDEX customers_region RENAME TO customers_area", command));
    assertEquals(SqlCommandType.ALTER_INDEX_RENAME, command.type());
    assertName("customers_region", command.indexName());
    assertName("customers_area", command.renamedIndexName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE SEQUENCE invoice_ids INCREMENT BY -3 START WITH 100",
            command));
    assertEquals(SqlCommandType.CREATE_SEQUENCE, command.type());
    assertName("invoice_ids", command.sequenceName());
    assertEquals(100, command.sequenceStart());
    assertEquals(-3, command.sequenceIncrement());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT NEXT VALUE FOR invoice_ids", command));
    assertEquals(SqlCommandType.NEXT_SEQUENCE_VALUE, command.type());
    assertName("invoice_ids", command.sequenceName());
    assertEquals(
        StatusCode.OK,
        parser.parse("DROP SEQUENCE invoice_ids", command));
    assertEquals(SqlCommandType.DROP_SEQUENCE, command.type());
    assertName("invoice_ids", command.sequenceName());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE SEQUENCE invalid INCREMENT BY 0", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE VIEW active_accounts AS "
                + "SELECT id, region FROM accounts WHERE balance>0;",
            command));
    assertEquals(SqlCommandType.CREATE_VIEW, command.type());
    assertName("active_accounts", command.tableName());
    assertText(
        "SELECT id, region FROM accounts WHERE balance>0",
        command.viewQuery());
    assertEquals(
        StatusCode.OK,
        parser.parse("DROP VIEW active_accounts", command));
    assertEquals(SqlCommandType.DROP_VIEW, command.type());
    assertName("active_accounts", command.tableName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE events (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                + "payload BIGINT NOT NULL)",
            command));
    assertEquals(SqlCommandType.CREATE_TABLE, command.type());
    assertEquals(true, command.hasPrimaryKeyIdentity());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE invalid_identity "
                + "(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, payload BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE bounded_accounts "
                + "(id BIGINT CHECK (id > 0) PRIMARY KEY, "
                + "balance BIGINT CHECK (balance >= -100))",
            command));
    assertEquals(true, command.columnHasCheck(0));
    assertEquals(SqlComparison.GREATER_THAN, command.columnCheckComparison(0));
    assertEquals(0, command.columnCheckValue(0));
    assertEquals(true, command.columnHasCheck(1));
    assertEquals(SqlComparison.GREATER_OR_EQUAL, command.columnCheckComparison(1));
    assertEquals(-100, command.columnCheckValue(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE contacts "
                + "(id BIGINT PRIMARY KEY, email BIGINT UNIQUE, alias VARCHAR(7) UNIQUE)",
            command));
    assertEquals(true, command.columnIsUnique(1));
    assertEquals(true, command.columnIsUnique(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE orders "
                + "(id BIGINT PRIMARY KEY, account_id BIGINT REFERENCES accounts(id), "
                + "external_id BIGINT UNIQUE REFERENCES external_accounts(id))",
            command));
    assertEquals(true, command.columnHasReference(1));
    assertName("accounts", command.columnReferenceTableName(1));
    assertName("id", command.columnReferenceColumnName(1));
    assertEquals(true, command.columnHasReference(2));
    assertEquals(true, command.columnIsUnique(2));
    assertName("external_accounts", command.columnReferenceTableName(2));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE duplicate_reference "
                + "(id BIGINT PRIMARY KEY, account_id BIGINT "
                + "REFERENCES accounts(id) REFERENCES accounts(id))",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE text_reference "
                + "(id BIGINT PRIMARY KEY, account_id VARCHAR(7) REFERENCES accounts(id))",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE excessive_references "
                + "(id BIGINT PRIMARY KEY, a BIGINT REFERENCES parents(id), "
                + "b BIGINT REFERENCES parents(id), c BIGINT REFERENCES parents(id), "
                + "d BIGINT REFERENCES parents(id), e BIGINT REFERENCES parents(id))",
            command));
    assertTrue(command.columnHasReference(5));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE reserved_reference "
                + "(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES _river_parent(id))",
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE duplicate_unique "
                + "(id BIGINT PRIMARY KEY, value BIGINT UNIQUE UNIQUE)",
            command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("DROP INDEX _river_unique_1_1 ON contacts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE TABLE _river_reserved (id BIGINT PRIMARY KEY, value BIGINT)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE wrong_check "
                + "(id BIGINT PRIMARY KEY, value BIGINT CHECK (id > 0))",
            command));
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
            "INSERT INTO ledger VALUES (3, DEFAULT, NULL)", command));
    assertTrue(command.insertIsDefault(0, 1));
    assertFalse(command.insertIsDefault(0, 2));
    assertTrue(command.insertIsNull(0, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO labels VALUES (1, 'river', 'it''s')", command));
    assertEquals(SqlTypeDescriptor.varchar(5), command.insertTypeDescriptor(0, 1));
    assertText("river", command, command.insertValue(0, 1));
    assertText("it's", command, command.insertValue(0, 2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO products (id, code, qty) VALUES "
                + "(1, 'beta', 10), (2, 'alpha', 20)",
            command));
    assertEquals(3, command.columnCount());
    assertEquals(SqlTypeDescriptor.varchar(4), command.insertTypeDescriptor(0, 1));
    assertEquals(SqlTypeDescriptor.INTEGER, command.insertTypeDescriptor(0, 2));
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
    assertEquals(SqlCommandType.SCAN, command.type());
    assertName("value", command.firstColumnName());
    assertName("key", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertEquals(StatusCode.OK, parser.parse("SELECT COUNT(*) FROM accounts", command));
    assertEquals(SqlCommandType.COUNT, command.type());
    assertName("accounts", command.tableName());
    assertEquals(false, command.hasPredicate());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT COUNT(*) FROM accounts WHERE region=7", command));
    assertEquals(SqlCommandType.COUNT, command.type());
    assertEquals(true, command.hasPredicate());
    assertEquals(true, (predicateComparison(command, 0) == SqlComparison.EQUAL));
    assertName("region", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(*) FROM accounts WHERE region >= -2 AND region < 9",
            command));
    assertEquals(true, command.hasPredicate());
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(-2, predicateValue(command, 0));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 1));
    assertEquals(9, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(a.balance) AS present FROM accounts a WHERE a.region=7",
            command));
    assertEquals(SqlCommandType.COUNT_VALUE, command.type());
    assertName("a", command.columnTableName(0));
    assertName("balance", command.columnName(0));
    assertName("present", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(DISTINCT a.balance) AS unique_balance FROM accounts a",
            command));
    assertEquals(SqlCommandType.COUNT_DISTINCT, command.type());
    assertEquals(SqlAggregateKind.COUNT_DISTINCT, command.aggregateKind(0));
    assertEquals(0, command.aggregateOperandProjection(0));
    assertName("balance", command.columnName(0));
    assertName("unique_balance", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT SUM(a.balance) AS total FROM accounts a "
                + "WHERE a.region>=7 AND a.region<9",
            command));
    assertEquals(SqlCommandType.SUM, command.type());
    assertName("accounts", command.tableName());
    assertName("a", command.tableAlias());
    assertName("a", command.columnTableName(0));
    assertName("balance", command.columnName(0));
    assertName("total", command.columnAlias(0));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT MIN(a.balance) AS lowest FROM accounts a", command));
    assertEquals(SqlCommandType.MIN, command.type());
    assertName("a", command.columnTableName(0));
    assertName("balance", command.columnName(0));
    assertName("lowest", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT MAX(balance) highest FROM accounts", command));
    assertEquals(SqlCommandType.MAX, command.type());
    assertName("balance", command.columnName(0));
    assertName("highest", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT COUNT(EXTRACT(DAY FROM observed)) AS days FROM accounts",
            command));
    assertEquals(SqlCommandType.COUNT_VALUE, command.type());
    assertPostfix(
        command.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertTrue(command.projectionExpression(0).hasColumnReference());
    assertName("days", command.columnAlias(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT MIN(CAST(observed AS TIMESTAMP(3))) FROM accounts",
            command));
    assertPostfix(
        command.projectionExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.CAST);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MIN(EXTRACT(DAY FROM observed)) FROM accounts "
                + "GROUP BY region",
            command));
    assertPostfix(
        command.projectionExpression(1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.EXTRACT);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(observed AT TIME ZONE 'UTC') FROM accounts "
                + "GROUP BY region HAVING MAX(observed AT TIME ZONE 'UTC')>="
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(day+(CAST('2024-01-01' AS DATE)-day)) FROM accounts "
                + "GROUP BY region HAVING "
                + "MAX(day+(CAST('2024-01-01' AS DATE)-day))>=DATE '2024-01-01'",
            command));
    assertEquals(1, command.booleanHavingPredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(observed AT TIME ZONE 'UTC') FROM accounts "
                + "GROUP BY region HAVING MAX(observed AT TIME ZONE '+01:00')>="
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'",
            command));
    assertEquals(2, command.aggregateInvocationCount());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT SUM(*) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT SUM(balance, region) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT SUM(balance AS total) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT MIN(*) FROM accounts", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT MAX(balance, region) FROM accounts", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT COUNT(*) AS total FROM accounts", command));
    assertName("total", command.columnAlias(0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT COUNT(balance AS total) FROM accounts", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM accounts WHERE region IN (7, -2, 7, NULL) AND id NOT IN (9)",
            command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_MEMBERSHIP,
        command.wherePredicates().leafTest(0));
    assertEquals(4, membershipCount(command, 0));
    assertEquals(7, membershipValue(command, 0, 0));
    assertEquals(-2, membershipValue(command, 0, 1));
    assertEquals(7, membershipValue(command, 0, 2));
    assertTrue(membershipHasNull(command, 0));
    assertTrue(command.wherePredicates().leafNegated(1));
    assertEquals(1, membershipCount(command, 1));
    assertEquals(9, membershipValue(command, 1, 0));
    assertFalse(membershipHasNull(command, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM accounts WHERE region IN ()", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT id FROM accounts WHERE region IN (1,)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM accounts WHERE region BETWEEN -2 AND 9", command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
    assertEquals(-2, predicateLower(command, 0));
    assertEquals(9, predicateUpper(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT id FROM accounts WHERE region BETWEEN 7 AND 9223372036854775807",
            command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
    assertEquals(7, predicateLower(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM accounts WHERE region BETWEEN 9 AND -2", command));
    assertEquals(
        SqlBooleanPredicateProgram.TEST_BETWEEN,
        command.wherePredicates().leafTest(0));
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
    assertEquals(3, command.wherePredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, SUM(balance) AS total FROM accounts "
                + "GROUP BY region ORDER BY region LIMIT 5",
            command));
    assertEquals(SqlCommandType.GROUP_SUM, command.type());
    assertName("region", command.columnName(0));
    assertName("balance", command.columnName(1));
    assertName("total", command.columnAlias(1));
    assertEquals(5, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, SUM(balance) AS total FROM accounts "
                + "GROUP BY region HAVING SUM(balance) >= 100 "
                + "ORDER BY region LIMIT 5",
            command));
    assertEquals(1, command.booleanHavingPredicates().leafCount());
    assertEquals(SqlComparison.GREATER_OR_EQUAL, havingComparison(command, 0));
    assertEquals(100, havingValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts "
                + "GROUP BY region HAVING COUNT(*) <> 1",
            command));
    assertEquals(SqlComparison.NOT_EQUAL, havingComparison(command, 0));
    assertEquals(1, havingValue(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, SUM(balance) FROM accounts "
                + "GROUP BY region HAVING COUNT(*) > 1",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(balance) FROM accounts GROUP BY region",
            command));
    assertEquals(SqlCommandType.GROUP_COUNT_VALUE, command.type());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MIN(accounts.balance) FROM accounts GROUP BY region",
            command));
    assertEquals(SqlCommandType.GROUP_MIN, command.type());
    assertName("accounts", command.columnTableName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, MAX(balance) FROM accounts GROUP BY region",
            command));
    assertEquals(SqlCommandType.GROUP_MAX, command.type());
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
    assertName("accounts", predicateTableName(command, 0));
    assertName("region", predicateColumnName(command, 0));
    assertEquals(SqlComparison.GREATER_OR_EQUAL, predicateComparison(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertEquals(SqlComparison.LESS_THAN, predicateComparison(command, 1));
    assertEquals(9, predicateValue(command, 1));
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
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(2, command.rowLimit());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT DISTINCT region, key FROM accounts", command));
    assertEquals(2, command.columnCount());
    assertName("region", command.columnName(0));
    assertName("key", command.columnName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT key FROM accounts ORDER BY key DESC", command));
    assertTrue(command.isDescendingOrder());
    assertName("key", command.orderColumnName());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT region, COUNT(*) FROM accounts "
                + "GROUP BY region ORDER BY region DESC",
            command));
    assertTrue(command.isDescendingOrder());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT DISTINCT region FROM accounts ORDER BY region DESC",
            command));
    assertTrue(command.isDescendingOrder());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key, regions.code FROM accounts "
                + "JOIN regions ON accounts.region=regions.id",
            command));
    assertEquals(SqlCommandType.JOIN_SCAN, command.type());
    assertName("accounts", command.tableName());
    assertName("regions", command.joinChain().tableName(1));
    assertEquals(1, command.joinChain().onPredicates(0).leafCount());
    assertEquals(
        SqlComparison.EQUAL,
        command.joinChain().onPredicates(0).comparison(0));
    int onLeft = (int) command.joinChain().onPredicates(0).programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0);
    int onRight = (int) command.joinChain().onPredicates(0).programOperand(
        0, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0);
    assertName("accounts", command.predicateSymbolTable(onLeft));
    assertName("region", command.predicateSymbolName(onLeft));
    assertName("regions", command.predicateSymbolTable(onRight));
    assertName("id", command.predicateSymbolName(onRight));
    assertName("accounts", command.columnTableName(0));
    assertName("key", command.columnName(0));
    assertName("regions", command.columnTableName(1));
    assertName("code", command.columnName(1));
    assertFalse(command.joinChain().isLeft(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key, regions.code FROM accounts "
                + "LEFT OUTER JOIN regions ON accounts.region=regions.id",
            command));
    assertEquals(SqlCommandType.JOIN_SCAN, command.type());
    assertTrue(command.joinChain().isLeft(0));
    assertName("regions", command.joinChain().tableName(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key, r.code FROM accounts a "
                + "JOIN regions AS r ON a.region=r.id "
                + "WHERE a.region=7 AND r.code>=7000",
            command));
    assertName("a", command.tableAlias());
    assertName("r", command.joinChain().alias(1));
    assertName("a", command.columnTableName(0));
    assertName("r", command.columnTableName(1));
    assertName("a", predicateTableName(command, 0));
    assertName("r", predicateTableName(command, 1));
    assertEquals(1, command.joinChain().onPredicates(0).leafCount());
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT a.key+1 AS next_key,r.code FROM accounts a "
                + "LEFT JOIN regions r ON NOT (a.region+1<>r.id OR r.code<0) "
                + "WHERE a.key+r.id>2",
            command));
    assertTrue(command.joinChain().isLeft(0));
    assertEquals(2, command.joinChain().onPredicates(0).leafCount());
    assertEquals(1, command.wherePredicates().leafCount());
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT region, key, value FROM accounts WHERE key=7", command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(3, command.columnCount());
    assertName("region", command.columnName(0));
    assertName("value", command.columnName(2));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE key >= 11 AND key < 29",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertFalse(command.isBoundedScan());
    assertEquals(11, predicateValue(command, 0));
    assertEquals(29, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE value = 701",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(701, predicateValue(command, 0));
    assertName("key", command.firstColumnName());
    assertName("value", command.secondColumnName());
    assertName("value", predicateColumnName(command, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key, value FROM accounts WHERE value >= -50 AND value < 75",
            command));
    assertEquals(SqlCommandType.SCAN, command.type());
    assertEquals(-50, predicateValue(command, 0));
    assertEquals(75, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key FROM accounts WHERE region=7 "
                + "AND value >= 100 AND value < 300 AND key=2",
            command));
    assertEquals(4, command.wherePredicates().leafCount());
    assertName("region", predicateColumnName(command, 0));
    assertEquals(7, predicateValue(command, 0));
    assertName("value", predicateColumnName(command, 1));
    assertEquals(100, predicateValue(command, 1));
    assertName("value", predicateColumnName(command, 2));
    assertEquals(300, predicateValue(command, 2));
    assertName("key", predicateColumnName(command, 3));
    assertEquals(2, predicateValue(command, 3));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT accounts.key FROM accounts "
                + "JOIN regions ON accounts.region=regions.id "
                + "WHERE accounts.region=7 AND accounts.value >= 100 "
                + "AND accounts.value < 300 AND regions.code=7000",
            command));
    assertEquals(4, command.wherePredicates().leafCount());
    assertName("accounts", predicateTableName(command, 1));
    assertName("value", predicateColumnName(command, 1));
    assertName("regions", predicateTableName(command, 3));
    assertName("code", predicateColumnName(command, 3));
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
    assertFalse(command.updateHasExpression(0));
    assertFalse(command.updateHasExpression(1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=balance+25, region=id-2 WHERE key=7",
            command));
    assertEquals(2, command.updateColumnCount());
    assertTrue(command.updateHasExpression(0));
    assertTrue(command.updateHasExpression(1));
    assertEquals(2, command.mutationExpressionCount());
    assertMutationPostfix(
        command,
        command.updateExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertMutationPostfix(
        command,
        command.updateExpression(1),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.SUBTRACT);
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
            "UPDATE accounts SET balance=DEFAULT, region=NULL WHERE key=7",
            command));
    assertTrue(command.updateIsDefault(0));
    assertFalse(command.updateIsNull(0));
    assertFalse(command.updateIsDefault(1));
    assertTrue(command.updateIsNull(1));
    assertEquals(
        StatusCode.OK,
        parser.parse("UPDATE labels SET code='fresh' WHERE id=1", command));
    assertEquals(SqlTypeDescriptor.varchar(5), command.updateTypeDescriptor(0));
    assertText("fresh", command, command.updateValue(0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET region=9 WHERE balance >= 100 AND balance < 500",
            command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(100, predicateValue(command, 0));
    assertEquals(500, predicateValue(command, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse("UPDATE accounts SET balance=balance WHERE key=7", command));
    assertTrue(command.updateHasExpression(0));
    assertEquals(
        StatusCode.OK,
        parser.parse("UPDATE accounts SET balance=balance*2 WHERE key=7", command));
    assertMutationPostfix(
        command,
        command.updateExpression(0),
        SqlScalarExpression.COLUMN,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.MULTIPLY);
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO accounts VALUES (1+2,-9223372036854775808),"
                + "(4,5)",
            command));
    assertTrue(command.insertHasExpression(0, 0));
    assertMutationPostfix(
        command,
        command.insertExpression(0, 0),
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(Long.MIN_VALUE, command.insertValue(0, 1));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "UPDATE accounts SET balance=-9223372036854775808+1 WHERE key=7",
            command));
    assertMutationPostfix(
        command,
        command.updateExpression(0),
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.LITERAL,
        SqlScalarExpression.ADD);
    assertEquals(
        Long.MIN_VALUE,
        command.mutationExpressionOperand(command.updateExpression(0), 0));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "INSERT INTO accounts VALUES"
                + "(1+1,2+2,3+3,4+4,5+5,6+6,7+7,8+8),"
                + "(9+9,10+10,11+11,12+12,13+13,14+14,15+15,16+16)",
            command));
    assertEquals(2, command.insertRowCount());
    assertEquals(8, command.insertColumnCount());
    assertEquals(StatusCode.OK, parser.parse("DELETE FROM accounts WHERE key = 7", command));
    assertEquals(SqlCommandType.DELETE, command.type());
    assertEquals(true, (predicateComparison(command, 0) == SqlComparison.EQUAL));
    assertEquals(
        StatusCode.OK,
        parser.parse("DELETE FROM accounts WHERE key >= 10 AND key < 20", command));
    assertEquals(2, command.wherePredicates().leafCount());
    assertEquals(10, predicateValue(command, 0));
    assertEquals(20, predicateValue(command, 1));
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
  void parsesStreamingCatalogQueries() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();

    assertEquals(StatusCode.OK, parser.parseQuery("SHOW TABLES;", query, command));
    assertEquals(SqlCommandType.SHOW_TABLES, command.type());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW TABLE", query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW TABLES EXTRA", query, command));
    assertEquals(
        StatusCode.OK,
        parser.parseQuery("SHOW INDEXES FROM accounts", query, command));
    assertEquals(SqlCommandType.SHOW_INDEXES, command.type());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.OK,
        parser.parseQuery("SHOW COLUMNS FROM accounts", query, command));
    assertEquals(SqlCommandType.SHOW_COLUMNS, command.type());
    assertName("accounts", command.tableName());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW INDEXES accounts", query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW COLUMNS accounts", query, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parseQuery("SHOW COLUMNS FROM accounts EXTRA", query, command));
  }

  @Test
  void parsesAnalyzeTableAndRecoversFromMalformedInput() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse("ANALYZE accounts", command));
    assertEquals(SqlCommandType.ANALYZE_TABLE, command.type());
    assertName("accounts", command.tableName());
    assertEquals(StatusCode.OK, parser.parse("ANALYZE TABLE regions", command));
    assertEquals(SqlCommandType.ANALYZE_TABLE, command.type());
    assertName("regions", command.tableName());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("ANALYZE", command));
    assertEquals(StatusCode.OK, parser.parse("ANALYZE countries", command));
    assertName("countries", command.tableName());
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
        StatusCode.OK,
        parser.parse("CREATE TABLE only_key (id BIGINT PRIMARY KEY)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse(
            "CREATE TABLE bad_default "
                + "(id BIGINT PRIMARY KEY, value BIGINT DEFAULT 1 DEFAULT 2)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE bad_primary_default "
                + "(id BIGINT DEFAULT 1 PRIMARY KEY, value BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "CREATE TABLE text_key (id VARCHAR(7) PRIMARY KEY, value BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO labels VALUES (1, 'ninechars', NULL)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("SELECT id FROM labels WHERE code IN ('one', 2)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO x VALUES (9223372036854775808, 1)", command));
    assertEquals(SqlTypeDescriptor.decimal(19, 0), command.insertTypeDescriptor(0, 0));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO x VALUES (0, -9223372036854775808)", command));
    assertEquals(Long.MIN_VALUE, command.value());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("DROP TABLE", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("SELECT key, value FROM x WHERE key ! 1", command));
    assertEquals(
        StatusCode.OK,
        parser.parse(
            "SELECT key FROM x WHERE a=1 AND b=2 AND c=3 AND d=4 "
                + "AND e=5 AND f=6 AND g=7 AND h=8 AND i=9",
            command));
    assertEquals(9, command.wherePredicates().leafCount());
  }

  @Test
  void acceptsMaximumIdentifierAndRejectsTrailingInput() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    String maximumName = "a".repeat(SqlIdentifier.MAXIMUM_LENGTH);

    assertEquals(StatusCode.OK, parser.parse("DROP TABLE " + maximumName, command));
    assertTrue(command.isAvailable());
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        parser.parse("DROP TABLE " + maximumName + "a trailing", command));
    assertFalse(command.isAvailable());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("DROP TABLE " + maximumName + " trailing", command));
    assertFalse(command.isAvailable());
  }

  @Test
  void acceptsInsertRowsBeyondLegacyFixtureBatchSize() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    int rowCount = 257;
    StringBuilder rows = new StringBuilder("INSERT INTO x VALUES ");
    for (int index = 0; index < rowCount; index++) {
      if (index > 0) {
        rows.append(',');
      }
      rows.append('(').append(index).append(',').append(index).append(')');
    }
    assertEquals(StatusCode.OK, parser.parse(rows, command));
    assertEquals(rowCount, command.insertRowCount());
    assertEquals(256, command.insertValue(256, 0));
    assertEquals(256, command.insertValue(256, 1));
    assertTrue(command.isAvailable());
  }
  private static void assertCompositeIndexColumns(SqlParser parser, SqlCommand command) {
    StringBuilder maximum = new StringBuilder("CREATE UNIQUE INDEX wide ON records(");
    for (int part = 0; part < 32; part++) {
      if (part > 0) maximum.append(',');
      maximum.append('c').append(part + 1);
    }
    maximum.append(')');
    assertEquals(StatusCode.OK, parser.parse(maximum, command));
    assertEquals(32, command.columnCount());
    assertName("c32", command.columnName(31));

    maximum.insert(maximum.length() - 1, ",c33");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(maximum, command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE INDEX empty ON records()", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE INDEX trailing ON records(c1,)", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("CREATE INDEX duplicate ON records(c1,C1)", command));
  }

}
