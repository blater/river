package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlNumericKeyTypeTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e554d455249434bL, 0x4559545950453031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void decimal38IsAFirstClassCompositeKeyAndForeignKey(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    String positive = "123456789012345678901234567890.12";
    String negative = "-999999999999999999999999999999.99";
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE decimal_parent (tenant INTEGER,amount DECIMAL(38,2),"
            + "payload DOUBLE PRECISION,PRIMARY KEY(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX decimal_amount ON decimal_parent(amount,tenant)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE decimal_child (id BIGINT PRIMARY KEY,tenant INTEGER,"
            + "amount DECIMAL(38,2),FOREIGN KEY(tenant,amount) "
            + "REFERENCES decimal_parent(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO decimal_parent VALUES (1," + positive + ",1.25),"
            + "(2," + negative + ",-2.5)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO decimal_child VALUES (10,1," + positive + ")", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "INSERT INTO decimal_child VALUES (11,1," + negative + ")", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM decimal_parent WHERE tenant=1 AND amount=" + positive,
        result));
    assertDecimal128(result, positive, 38, 2);
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    database = open(root);
    session = session(database);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM decimal_parent WHERE tenant=2 AND amount=" + negative,
        result));
    assertDecimal128(result, negative, 38, 2);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void highScaleDecimalIsAFirstClassCompositeKeyAndForeignKey(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    String maximum = "9999.999999999999999999";
    String negative = "-0.000000000000000001";
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE scaled_parent (tenant INTEGER,amount DECIMAL(22,18),"
            + "PRIMARY KEY(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX scaled_amount ON scaled_parent(amount,tenant)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE scaled_child (tenant INTEGER,amount DECIMAL(22,18),"
            + "FOREIGN KEY(tenant,amount) REFERENCES scaled_parent(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE scaled_keyless (amount DECIMAL(22,18))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO scaled_parent VALUES (1," + maximum + "),(2," + negative + ")",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO scaled_child VALUES (1," + maximum + ")", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "INSERT INTO scaled_child VALUES (1," + negative + ")", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO scaled_keyless VALUES (" + maximum + "),(" + negative + ")", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM scaled_parent WHERE tenant=2 AND amount=" + negative,
        result));
    assertDecimal128(result, negative, 22, 18);
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    database = open(root);
    session = session(database);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM scaled_parent WHERE tenant=1 AND amount=" + maximum,
        result));
    assertDecimal128(result, maximum, 22, 18);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void wideDecimalExpressionsAndAggregatesPreserveBothLanes(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "SELECT 99999999999999999999999999999999999999", result));
    assertDecimal128(
        result, "99999999999999999999999999999999999999", 38, 0);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT 9999.999999999999999999+0.000000000000000001", result));
    assertDecimal128(result, "10000.000000000000000000", 23, 18);
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE decimal_values (amount DECIMAL(22,18))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO decimal_values VALUES "
            + "(9999.999999999999999999),(0.000000000000000001)", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT SUM(amount) FROM decimal_values", result));
    assertDecimal128(result, "10000.000000000000000000", 38, 18);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT AVG(amount) FROM decimal_values", result));
    assertDecimal128(result, "5000.000000000000000000", 38, 18);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT MIN(amount) FROM decimal_values", result));
    assertDecimal128(result, "0.000000000000000001", 22, 18);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT MAX(amount) FROM decimal_values", result));
    assertDecimal128(result, "9999.999999999999999999", 22, 18);
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE decimal_groups (amount DECIMAL(22,18))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO decimal_groups VALUES (0.000000000000000001),"
            + "(18.446744073709551617),(0.000000000000000001)", result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT amount,COUNT(*) FROM decimal_groups GROUP BY amount ORDER BY amount",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertDecimal128(row, "0.000000000000000001", 22, 18);
    assertEquals(2, row.valueAt(1));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertDecimal128(row, "18.446744073709551617", 22, 18);
    assertEquals(1, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void legacyCheckTablePublishesWideLiteralAndComputedProjections(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    String create = "CREATE TABLE legacy_wide_projection ("
        + "id BIGINT PRIMARY KEY,value BIGINT,CHECK(1=1))";
    SqlCommand parsed = new SqlCommand();
    assertEquals(StatusCode.OK, new SqlParser().parse(create, parsed));
    assertTrue(SqlCreateTableLifecycleAdmission.requiresLegacy(parsed));
    assertEquals(StatusCode.OK, session.execute(create, result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO legacy_wide_projection VALUES (1,10),(2,20)", result));

    String maximum = "99999999999999999999999999999999999999";
    assertEquals(StatusCode.OK, session.execute(
        "SELECT " + maximum + " FROM legacy_wide_projection WHERE id=1", result));
    assertDecimal128(result, maximum, 38, 0);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT ROUND(1234.567890123456789012,18) "
            + "FROM legacy_wide_projection WHERE id=1", result));
    assertDecimal128(result, "1234.567890123456789012", 22, 18);

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT " + maximum + " FROM legacy_wide_projection ORDER BY id", cursor));
    for (int expected = 0; expected < 2; expected++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertDecimal128(row, maximum, 38, 0);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void decimal38Scale38AndWideOperatorsPreserveExactValues(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    String maximumFraction = "0.99999999999999999999999999999999999999";
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE fractional_boundaries (id INTEGER PRIMARY KEY,"
            + "amount DECIMAL(38,38))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX fractional_amount ON fractional_boundaries(amount)", result));
    SqlCommand parsedBoundary = new SqlCommand();
    assertEquals(StatusCode.OK, new SqlParser().parse(
        "INSERT INTO fractional_boundaries VALUES (1," + maximumFraction + ")",
        parsedBoundary));
    BigInteger boundary = new java.math.BigDecimal(maximumFraction).unscaledValue();
    assertEquals(SqlTypeDescriptor.decimal(38, 38),
        parsedBoundary.insertTypeDescriptor(0, 1));
    assertEquals(boundary.longValue(), parsedBoundary.insertValue(0, 1));
    assertEquals(boundary.shiftRight(Long.SIZE).longValue(),
        parsedBoundary.insertValueHigh(0, 1));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO fractional_boundaries VALUES (1," + maximumFraction + ")", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO fractional_boundaries VALUES (2,-" + maximumFraction + ")", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM fractional_boundaries WHERE amount=" + maximumFraction,
        result));
    assertDecimal128(result, maximumFraction, 38, 38);

    assertEquals(StatusCode.OK, session.execute(
        "SELECT 123456789012345678.12*2.00", result));
    assertDecimal128(result, "246913578024691356.2400", 23, 4);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT 999999999999999999.00/3.00", result));
    assertDecimal128(result, "333333333333333333.000000", 26, 6);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT 999999999999999998.00%7.00", result));
    assertDecimal128(result, "6.00", 3, 2);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT CEIL(9999.000000000000000001)", result));
    assertDecimal128(result, "10000", 38, 0);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT FLOOR(-0.000000000000000001)", result));
    assertDecimal128(result, "-1", 38, 0);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT ROUND(1234.567890123456789012,12)", result));
    assertDecimal128(result, "1234.567890123457", 16, 12);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT TRUNCATE(-1234.567890123456789012,12)", result));
    assertDecimal128(result, "-1234.567890123456", 16, 12);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void descriptorUpdatesEvaluateWideAndApproximateNumericExpressions(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_updates (id INTEGER PRIMARY KEY,"
            + "amount DECIMAL(22,18),single_value REAL,approximate DOUBLE PRECISION)",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_updates VALUES (1,1234.500000000000000000,1.25,2.5)",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE numeric_updates SET amount=amount*2.00 WHERE id=1", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM numeric_updates WHERE id=1", result));
    assertDecimal128(result, "2469.000000000000000000", 22, 18);
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE numeric_updates SET "
            + "amount=CAST(single_value AS DECIMAL(22,18)),approximate=amount "
            + "WHERE id=1", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM numeric_updates WHERE id=1", result));
    assertDecimal128(result, "1.250000000000000000", 22, 18);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT approximate FROM numeric_updates WHERE id=1", result));
    assertEquals(SqlTypeDescriptor.DOUBLE, result.typeDescriptorAt(0));
    assertEquals(Double.doubleToRawLongBits(2469.0d), result.valueAt(0));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE numeric_updates SET amount=CAST(approximate AS DECIMAL(22,18)),"
            + "single_value=amount WHERE id=1", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM numeric_updates WHERE id=1", result));
    assertDecimal128(result, "2469.000000000000000000", 22, 18);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT single_value FROM numeric_updates WHERE id=1", result));
    assertEquals(SqlTypeDescriptor.REAL, result.typeDescriptorAt(0));
    assertEquals(Integer.toUnsignedLong(Float.floatToRawIntBits(1.25f)), result.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void approximateArithmeticAndScaleFunctionsExecuteAsFirstClassExpressions(
      @TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertApproximate(session, result,
        "CAST(5.5 AS REAL)+CAST(2.0 AS REAL)", 7.5d, SqlTypeDescriptor.REAL);
    assertApproximate(session, result,
        "CAST(5.5 AS REAL)-CAST(2.0 AS DOUBLE PRECISION)",
        3.5d, SqlTypeDescriptor.DOUBLE);
    assertApproximate(session, result,
        "CAST(5.5 AS REAL)*CAST(2.0 AS DOUBLE PRECISION)",
        11.0d, SqlTypeDescriptor.DOUBLE);
    assertApproximate(session, result,
        "CAST(5.5 AS REAL)/CAST(2.0 AS DOUBLE PRECISION)",
        2.75d, SqlTypeDescriptor.DOUBLE);
    assertApproximate(session, result,
        "CAST(5.5 AS REAL)%CAST(2.0 AS DOUBLE PRECISION)",
        1.5d, SqlTypeDescriptor.DOUBLE);
    assertApproximate(session, result,
        "ROUND(CAST(1.256 AS DOUBLE PRECISION),2)",
        1.26d, SqlTypeDescriptor.DOUBLE);
    assertApproximate(session, result,
        "TRUNCATE(CAST(-1.259 AS REAL),2)",
        -1.25d, SqlTypeDescriptor.REAL);
    assertEquals(StatusCode.DIVISION_BY_ZERO, session.execute(
        "SELECT CAST(1.0 AS DOUBLE PRECISION)/CAST(0.0 AS REAL)", result));

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_expressions (id INTEGER PRIMARY KEY,"
            + "real_value REAL,double_value DOUBLE PRECISION)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_expressions VALUES (1,1.25,2.5)", result));
    assertApproximate(session, result,
        "real_value*2+double_value FROM approximate_expressions WHERE id=1",
        5.0d, SqlTypeDescriptor.DOUBLE);
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE approximate_expressions "
            + "SET double_value=ROUND(double_value/3.0,2) WHERE id=1", result));
    assertApproximate(session, result,
        "double_value FROM approximate_expressions WHERE id=1",
        0.83d, SqlTypeDescriptor.DOUBLE);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void wideDecimalPredicatesOrderingAndParametersUseBothLanes(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE decimal_predicates (id INTEGER PRIMARY KEY,"
            + "amount DECIMAL(22,18))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX decimal_predicates_amount ON decimal_predicates(amount)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO decimal_predicates VALUES "
            + "(1,-0.000000000000000001),(2,0.000000000000000001),"
            + "(3,18.446744073709551617),(4,9999.999999999999999999),"
            + "(5,20.000000000000000000)",
        result));
    assertCount(session, "decimal_predicates", 1,
        "amount=0.000000000000000001");
    assertCount(session, "decimal_predicates", 4,
        "amount>0.000000000000000000");
    assertCount(session, "decimal_predicates", 1, "amount=20");
    assertCount(session, "decimal_predicates", 1, "amount=2.0E1");
    assertCount(session, "decimal_predicates", 2,
        "amount BETWEEN -0.000000000000000001 AND 0.000000000000000001");
    assertCount(session, "decimal_predicates", 2,
        "amount IN (0.000000000000000001,18.446744073709551617)");
    BigInteger parameter = new java.math.BigDecimal(
        "18.446744073709551617").unscaledValue();
    ParameterSet parameters = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, parameters.appendDecimal128(
        22, 18,
        parameter.shiftRight(Long.SIZE).longValue(), parameter.longValue()));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT amount FROM decimal_predicates WHERE amount=?", parameters, result));
    assertDecimal128(result, "18.446744073709551617", 22, 18);
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT amount FROM decimal_predicates ORDER BY amount DESC", cursor));
    String[] ordered = {
        "9999.999999999999999999",
        "20.000000000000000000",
        "18.446744073709551617",
        "0.000000000000000001",
        "-0.000000000000000001"
    };
    for (String value : ordered) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertDecimal128(row, value, 22, 18);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertDecimal128(
      SqlExecutionResult result, String decimal, int precision, int scale) {
    BigInteger unscaled = new java.math.BigDecimal(decimal).unscaledValue();
    assertEquals(unscaled.longValue(), result.valueAt(0));
    assertEquals(unscaled.shiftRight(Long.SIZE).longValue(), result.highValueAt(0));
    assertEquals(SqlTypeDescriptor.decimal(precision, scale), result.typeDescriptorAt(0));
  }

  private static void assertDecimal128(
      SqlScanRowResult result, String decimal, int precision, int scale) {
    BigInteger unscaled = new java.math.BigDecimal(decimal).unscaledValue();
    assertEquals(unscaled.longValue(), result.valueAt(0));
    assertEquals(unscaled.shiftRight(Long.SIZE).longValue(), result.highValueAt(0));
    assertEquals(SqlTypeDescriptor.decimal(precision, scale), result.typeDescriptorAt(0));
  }

  @Test
  void approximateLiteralsCoerceIntoCanonicalDescriptorValues() {
    SqlParser parser = new SqlParser();
    SqlCommand create = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse(
        "CREATE TABLE approximate_keys (single REAL,double_value DOUBLE PRECISION,"
            + "PRIMARY KEY(single,double_value))", create));
    SqlDescriptorTableBuilder builder = new SqlDescriptorTableBuilder();
    assertEquals(StatusCode.OK, builder.build(create, new StatusDetail(128)));
    TableDescriptor table = builder.descriptor();

    SqlCommand insert = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse(
        "INSERT INTO approximate_keys VALUES (1.25,-2.50)", insert));
    SqlDescriptorColumnMapping columns = new SqlDescriptorColumnMapping();
    SqlDescriptorMutationValues values = new SqlDescriptorMutationValues();
    assertEquals(StatusCode.OK, values.reserve(table));
    assertEquals(StatusCode.OK, columns.mapInsert(insert, table));
    assertEquals(StatusCode.OK, values.buildInsert(insert, table, columns, 0));
    assertEquals(SqlTypeDescriptor.REAL, values.mutation().descriptorAt(0));
    assertEquals(SqlTypeDescriptor.DOUBLE, values.mutation().descriptorAt(1));
  }

  @Test
  void exactNumericCompositeRowsReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_keys (small SMALLINT,normal INTEGER,wide BIGINT,"
            + "exact NUMERIC(12,2),PRIMARY KEY(small,normal,exact))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX numeric_wide ON numeric_keys(normal,wide,exact)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_keys VALUES (1,2,3,4.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_keys VALUES (-32768,2147483647,"
            + "9223372036854775807,9999999999.99)", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    database = open(root);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void approximateKeySchemaReopensWithoutRows(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_keys (small SMALLINT,normal INTEGER,wide BIGINT,"
            + "exact NUMERIC(12,2),PRIMARY KEY(small,normal,exact))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX numeric_wide ON numeric_keys(normal,wide,exact)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_keys VALUES (1,2,3,4.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_keys VALUES (-32768,2147483647,"
            + "9223372036854775807,9999999999.99)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_keys (single REAL,double_value DOUBLE PRECISION,"
            + "PRIMARY KEY(single,double_value))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX approximate_reverse "
            + "ON approximate_keys(double_value,single)", result));
    assertDistinctKeyIds(database);
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    database = open(root);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void approximateKeylessRowsReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_values (single REAL,double_value DOUBLE PRECISION)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_values VALUES (1.25,-2.50)", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    database = open(root);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void lossyRealPointPredicateFallsBackWithoutReturningWrongKey(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE lossy_real (value REAL,marker INTEGER,PRIMARY KEY(value,marker))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO lossy_real VALUES (16777216,1)", result));
    assertCount(session, "lossy_real", 1, "value=16777216 AND marker=1");
    assertCount(session, "lossy_real", 0, "value=16777217 AND marker=1");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void legacyApproximateSecondaryRangeFallsBackToNumericScan(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_ranges (id BIGINT PRIMARY KEY,value REAL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX approximate_ranges_value ON approximate_ranges(value)", result));
    ParameterSet values = new ParameterSet(6, 0);
    assertEquals(StatusCode.OK, values.appendBigint(1));
    assertEquals(StatusCode.OK, values.appendReal(-4.0f));
    assertEquals(StatusCode.OK, values.appendBigint(2));
    assertEquals(StatusCode.OK, values.appendReal(-1.0f));
    assertEquals(StatusCode.OK, values.appendBigint(3));
    assertEquals(StatusCode.OK, values.appendReal(2.0f));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_ranges VALUES (?,?),(?,?),(?,?)", values, result));
    assertCount(session, "approximate_ranges", 2, "value<0.0");
    assertCount(session, "approximate_ranges", 2, "value>=-1.0");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void typedNullRetainsItsNumericAssignmentDomain(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE typed_nulls (id BIGINT,marker INTEGER,value BIGINT,"
            + "PRIMARY KEY(id,marker))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO typed_nulls VALUES (1,1,2)", result));
    ParameterSet parameters = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, parameters.appendNull(SqlTypeDescriptor.DATE));
    assertEquals(StatusCode.DATATYPE_MISMATCH, session.execute(
        "UPDATE typed_nulls SET value=? WHERE id=1", parameters, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void oneApproximateCompositeKeyRowReopens(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_keys (single REAL,double_value DOUBLE PRECISION,"
            + "PRIMARY KEY(single,double_value))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX approximate_reverse "
            + "ON approximate_keys(double_value,single)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_keys VALUES (1.25,-2.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_keys VALUES (0.0,9.75)", result));
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO approximate_keys VALUES (1.25,-2.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE approximate_keys SET single=3.5 "
            + "WHERE single=1.25 AND double_value=-2.50", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    database = open(root);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void integerFamilyAndNumericAreDurableCompositeKeyParts(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_keys (small SMALLINT,normal INTEGER,wide BIGINT,"
            + "exact NUMERIC(12,2),PRIMARY KEY(small,normal,exact))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX numeric_wide ON numeric_keys(normal,wide,exact)", result));
    SqlCommand parsed = new SqlCommand();
    assertEquals(StatusCode.OK, new SqlParser().parse(
        "INSERT INTO numeric_keys VALUES (1,2,3,4.50)", parsed));
    assertEquals(SqlTypeDescriptor.INTEGER, parsed.insertTypeDescriptor(0, 0));
    assertEquals(SqlTypeDescriptor.INTEGER, parsed.insertTypeDescriptor(0, 1));
    assertEquals(SqlTypeDescriptor.INTEGER, parsed.insertTypeDescriptor(0, 2));
    assertEquals(SqlTypeDescriptor.decimal(3, 2), parsed.insertTypeDescriptor(0, 3));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_keys VALUES (1,2,3,4.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_keys VALUES (-32768,2147483647,"
            + "9223372036854775807,9999999999.99)", result));
    assertCount(session, 1, "small=1 AND normal=2 AND exact=4.50");
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO numeric_keys VALUES (5,2,3,4.50)", result));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, session.execute(
        "INSERT INTO numeric_keys VALUES (32768,7,8,9.00)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_keys (single REAL,double_value DOUBLE PRECISION,"
            + "PRIMARY KEY(single,double_value))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX approximate_reverse "
            + "ON approximate_keys(double_value,single)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_keys VALUES (1.25,-2.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_keys VALUES (0.0,9.75)", result));
    assertCount(session, "approximate_keys", 1,
        "single=1.25 AND double_value=-2.50");
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO approximate_keys VALUES (1.25,-2.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE approximate_keys SET single=3.5 "
            + "WHERE single=1.25 AND double_value=-2.50", result));
    assertCount(session, "approximate_keys", 0,
        "single=1.25 AND double_value=-2.50");
    assertCount(session, "approximate_keys", 1,
        "single=3.5 AND double_value=-2.50");
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertCount(session, 1, "small=-32768 AND normal=2147483647 "
        + "AND exact=9999999999.99");
    assertCount(session, "approximate_keys", 1,
        "single=3.5 AND double_value=-2.50");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void walOnlyReopenPreservesCompositeNumericAndApproximateKeys(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE exact_values (tenant INTEGER,amount NUMERIC(12,2),"
            + "PRIMARY KEY(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_values (real_key REAL,double_key DOUBLE PRECISION,"
            + "PRIMARY KEY(real_key,double_key))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO exact_values VALUES (7,12.50),(8,99.99)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_values VALUES (1.25,-2.50),(3.5,4.75)", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE exact_values SET amount=13.50 WHERE tenant=7", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE approximate_values SET real_key=6.25 WHERE double_key=4.75", result));
    // Deliberately omit CHECKPOINT: committed changes must be recovered from WAL.
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertCount(session, "exact_values", 1, "tenant=7 AND amount=13.50");
    assertCount(session, "exact_values", 1, "tenant=8 AND amount=99.99");
    assertCount(session, "approximate_values", 1,
        "real_key=1.25 AND double_key=-2.50");
    assertCount(session, "approximate_values", 1,
        "real_key=6.25 AND double_key=4.75");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void savepointRollbackRemovesCompositeNumericKeyIntents(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE exact_values (tenant INTEGER,amount NUMERIC(12,2),"
            + "PRIMARY KEY(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE approximate_values (real_key REAL,double_key DOUBLE PRECISION,"
            + "PRIMARY KEY(real_key,double_key))", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO exact_values VALUES (1,10.00)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_values VALUES (1.5,2.5)", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT before_second_rows", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO exact_values VALUES (2,20.00)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_values VALUES (3.5,4.5)", result));
    assertEquals(StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT before_second_rows", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertCount(session, "exact_values", 1, "tenant=1 AND amount=10.00");
    assertCount(session, "exact_values", 0, "tenant=2 AND amount=20.00");
    assertCount(session, "approximate_values", 1, "real_key=1.5 AND double_key=2.5");
    assertCount(session, "approximate_values", 0, "real_key=3.5 AND double_key=4.5");

    // Reusing both rolled-back keys proves that their unique-index intents were removed.
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO exact_values VALUES (2,20.00)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO approximate_values VALUES (3.5,4.5)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void savepointRollbackRestoresPriorCompositeKeyChangeThroughReopen(
      @TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE exact_values (tenant INTEGER,amount NUMERIC(12,2),"
            + "PRIMARY KEY(tenant,amount))", result));
    assertEquals(StatusCode.OK,
        session.execute("INSERT INTO exact_values VALUES (1,10.00)", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE exact_values SET amount=11.00 WHERE tenant=1 AND amount=10.00",
        result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT keep_changed_key", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE exact_values SET amount=10.00 WHERE tenant=1 AND amount=11.00",
        result));
    assertEquals(StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT keep_changed_key", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertCount(session, "exact_values", 0, "tenant=1 AND amount=10.00");
    assertCount(session, "exact_values", 1, "tenant=1 AND amount=11.00");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertCount(SqlSession session, long expected, String predicate) {
    assertCount(session, "numeric_keys", expected, predicate);
  }

  private static void assertCount(
      SqlSession session, String table, long expected, String predicate) {
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, result));
    assertEquals(expected, result.value());
  }

  private static void assertApproximate(
      SqlSession session, SqlExecutionResult result, String expression,
      double expected, int descriptor) {
    assertEquals(StatusCode.OK, session.execute("SELECT " + expression, result));
    assertEquals(descriptor, result.typeDescriptorAt(0));
    long bits = descriptor == SqlTypeDescriptor.REAL
        ? Integer.toUnsignedLong(Float.floatToRawIntBits((float) expected))
        : Double.doubleToRawLongBits(expected);
    assertEquals(bits, result.valueAt(0));
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static void assertDistinctKeyIds(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    RelationalSession session = opened.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    SchemaPin numeric = new SchemaPin();
    SchemaPin approximate = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.resolveDescriptor("numeric_keys", numeric, detail));
    assertEquals(StatusCode.OK,
        session.resolveDescriptor("approximate_keys", approximate, detail));
    assertNotEquals(
        numeric.descriptor().primaryKey().keyId(),
        approximate.descriptor().primaryKey().keyId());
    assertNotEquals(
        numeric.descriptor().secondaryKeyAt(0).keyId(),
        approximate.descriptor().secondaryKeyAt(0).keyId());
    assertEquals(StatusCode.OK, numeric.release());
    assertEquals(StatusCode.OK, approximate.release());
    assertEquals(StatusCode.OK, session.abort(new TransactionOutcome()));
  }

  private static RelationalDatabase open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
