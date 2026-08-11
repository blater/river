package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDriverTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a44424344524956L, 0x4552544553543031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void driverManagerExecutesStreamingSqlTransactionsAndDurableReopen(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    String url = url(server);

    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      assertTrue(connection.getAutoCommit());
      assertFalse(statement.execute(
          "CREATE TABLE accounts "
              + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)"));
      assertEquals(0, statement.getUpdateCount());
      assertFalse(statement.execute(
          "CREATE SEQUENCE jdbc_ids START WITH 40"));
      try (ResultSet sequence = statement.executeQuery(
          "SELECT NEXT VALUE FOR jdbc_ids")) {
        assertTrue(sequence.next());
        assertEquals(40, sequence.getLong(1));
        assertFalse(sequence.next());
      }
      assertEquals(
          3,
          statement.executeUpdate(
              "INSERT INTO accounts VALUES "
                  + "(1, 100, 7), (2, 200, 7), (3, 300, 8)"));
      try (ResultSet nullable = statement.executeQuery(
          "SELECT id, NULL FROM accounts WHERE id=1")) {
        assertTrue(nullable.next());
        assertEquals(1, nullable.getLong(1));
        assertFalse(nullable.wasNull());
        assertEquals(0, nullable.getLong(2));
        assertTrue(nullable.wasNull());
        assertNull(nullable.getString(2));
        assertTrue(nullable.wasNull());
        assertNull(nullable.getObject(2));
        assertTrue(nullable.wasNull());
        assertNull(nullable.getObject(2, Long.class));
        assertTrue(nullable.wasNull());
        assertFalse(nullable.next());
      }
      try (ResultSet membership = statement.executeQuery(
          "SELECT id FROM accounts WHERE balance IN "
              + "(SELECT balance FROM accounts WHERE region=7) ORDER BY id")) {
        assertTrue(membership.next());
        assertEquals(1, membership.getLong(1));
        assertTrue(membership.next());
        assertEquals(2, membership.getLong(1));
        assertFalse(membership.next());
      }
      try (ResultSet membership = statement.executeQuery(
          "SELECT id FROM accounts WHERE balance NOT IN "
              + "(SELECT balance FROM accounts WHERE region=7)")) {
        assertTrue(membership.next());
        assertEquals(3, membership.getLong(1));
        assertFalse(membership.next());
      }
      try (ResultSet unknown = statement.executeQuery(
          "SELECT id FROM accounts WHERE id NOT IN "
              + "(SELECT NULL FROM accounts WHERE id=1)")) {
        assertFalse(unknown.next());
      }
      try (ResultSet unknown = statement.executeQuery(
          "SELECT id FROM accounts WHERE id IN "
              + "(SELECT NULL FROM accounts WHERE id=1)")) {
        assertFalse(unknown.next());
      }
      try (ResultSet unknown = statement.executeQuery(
          "SELECT id FROM accounts WHERE id="
              + "(SELECT NULL FROM accounts WHERE id=1)")) {
        assertFalse(unknown.next());
      }
      try (ResultSet exists = statement.executeQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT NULL FROM accounts WHERE id=1) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(exists.next());
          assertEquals(expected, exists.getLong(1));
        }
        assertFalse(exists.next());
      }
      try (ResultSet empty = statement.executeQuery(
          "SELECT id FROM accounts WHERE id NOT IN "
              + "(SELECT id FROM accounts WHERE id=99) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(empty.next());
          assertEquals(expected, empty.getLong(1));
        }
        assertFalse(empty.next());
      }
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE nullable_values "
                  + "(id BIGINT PRIMARY KEY, value BIGINT, rank BIGINT)"));
      assertEquals(
          3,
          statement.executeUpdate(
              "INSERT INTO nullable_values VALUES "
                  + "(1, NULL, 3), (2, 20, NULL), (3, 30, 1)"));
      try (ResultSet nullableRows = statement.executeQuery(
          "SELECT id, value, rank FROM nullable_values ORDER BY value")) {
        assertTrue(nullableRows.next());
        assertEquals(1, nullableRows.getLong(1));
        assertEquals(0, nullableRows.getLong(2));
        assertTrue(nullableRows.wasNull());
        assertEquals(3, nullableRows.getLong(3));
        assertFalse(nullableRows.wasNull());
        assertTrue(nullableRows.next());
        assertEquals(2, nullableRows.getLong(1));
        assertEquals(20, nullableRows.getLong(2));
        assertEquals(0, nullableRows.getLong(3));
        assertTrue(nullableRows.wasNull());
        assertTrue(nullableRows.next());
        assertEquals(3, nullableRows.getLong(1));
        assertEquals(30, nullableRows.getLong(2));
        assertFalse(nullableRows.next());
      }
      try (ResultSet membership = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value IN "
              + "(SELECT value FROM nullable_values) ORDER BY id")) {
        assertTrue(membership.next());
        assertEquals(2, membership.getLong(1));
        assertTrue(membership.next());
        assertEquals(3, membership.getLong(1));
        assertFalse(membership.next());
      }
      try (ResultSet membership = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value NOT IN "
              + "(SELECT value FROM nullable_values)")) {
        assertFalse(membership.next());
      }
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE nullable_values SET value=NULL, rank=9 WHERE id=3"));
      try (ResultSet updated = statement.executeQuery(
          "SELECT value, rank FROM nullable_values WHERE id=3")) {
        assertTrue(updated.next());
        assertNull(updated.getObject(1));
        assertTrue(updated.wasNull());
        assertEquals(9, updated.getLong(2));
        assertFalse(updated.wasNull());
      }
      try (ResultSet nullValues = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value IS NULL ORDER BY id")) {
        assertTrue(nullValues.next());
        assertEquals(1, nullValues.getLong(1));
        assertTrue(nullValues.next());
        assertEquals(3, nullValues.getLong(1));
        assertFalse(nullValues.next());
      }
      try (ResultSet nonNullValues = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value IS NOT NULL")) {
        assertTrue(nonNullValues.next());
        assertEquals(2, nonNullValues.getLong(1));
        assertFalse(nonNullValues.next());
      }
      try (ResultSet nestedNullFilter = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value IN "
              + "(SELECT value FROM nullable_values "
              + "WHERE value IS NOT NULL)")) {
        assertTrue(nestedNullFilter.next());
        assertEquals(2, nestedNullFilter.getLong(1));
        assertFalse(nestedNullFilter.next());
      }
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX nullable_value_idx ON nullable_values(value)"));
      try (ResultSet nullComparison = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value=0")) {
        assertFalse(nullComparison.next());
      }
      assertEquals(
          "22000",
          assertThrows(
              SQLException.class,
              () -> statement.executeUpdate(
                  "INSERT INTO nullable_values VALUES (NULL, 4, 5)"))
              .getSQLState());
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE nullable_values SET value=10 WHERE id=1"));
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE nullable_values SET value=40 WHERE id=3"));
      try (ResultSet indexed = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value=40")) {
        assertTrue(indexed.next());
        assertEquals(3, indexed.getLong(1));
        assertFalse(indexed.next());
      }
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE nullable_values SET value=NULL WHERE id=2"));
      try (ResultSet indexedNull = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE value IS NULL ORDER BY id")) {
        assertTrue(indexedNull.next());
        assertEquals(2, indexedNull.getLong(1));
        assertFalse(indexedNull.next());
      }
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE regions (id BIGINT PRIMARY KEY, code BIGINT)"));
      assertEquals(
          2,
          statement.executeUpdate(
              "INSERT INTO regions VALUES (7, 7000), (8, 8000)"));
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE nullable_values SET rank=7 WHERE id=1"));
      try (ResultSet correlatedNull = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE EXISTS "
              + "(SELECT id FROM regions "
              + "WHERE regions.id=nullable_values.rank)")) {
        assertTrue(correlatedNull.next());
        assertEquals(1, correlatedNull.getLong(1));
        assertFalse(correlatedNull.next());
      }
      try (ResultSet correlatedNull = statement.executeQuery(
          "SELECT id FROM nullable_values WHERE rank NOT IN "
              + "(SELECT id FROM regions "
              + "WHERE regions.id=nullable_values.rank)")) {
        assertTrue(correlatedNull.next());
        assertEquals(3, correlatedNull.getLong(1));
        assertFalse(correlatedNull.next());
      }
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE region_labels "
                  + "(id BIGINT PRIMARY KEY, region BIGINT, code BIGINT)"));
      assertEquals(
          3,
          statement.executeUpdate(
              "INSERT INTO region_labels VALUES "
                  + "(1, 7, 7001), (2, 7, 7002), (3, 8, 8001)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX region_labels_region ON region_labels(region)"));
      try (ResultSet ordered = statement.executeQuery(
          "SELECT id, balance FROM accounts ORDER BY balance")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(ordered.next());
          assertEquals(expected, ordered.getLong("id"));
          assertEquals(expected * 100, ordered.getLong("balance"));
        }
        assertFalse(ordered.next());
      }
      try (ResultSet aliased = statement.executeQuery(
          "SELECT id AS account_id, balance funds FROM accounts "
              + "ORDER BY account_id")) {
        assertEquals("account_id", aliased.getMetaData().getColumnName(1));
        assertEquals("funds", aliased.getMetaData().getColumnName(2));
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(aliased.next());
          assertEquals(expected, aliased.getLong("account_id"));
          assertEquals(expected * 100, aliased.getLong("funds"));
        }
        assertFalse(aliased.next());
      }
      try (ResultSet derived = statement.executeQuery(
          "SELECT d.id, d.balance FROM "
              + "(SELECT id, balance, region FROM accounts WHERE accounts.region=7) d "
              + "WHERE d.balance >= 50 AND d.balance < 350 ORDER BY balance")) {
        assertTrue(derived.next());
        assertEquals(1, derived.getLong("id"));
        assertEquals(100, derived.getLong("balance"));
        assertTrue(derived.next());
        assertEquals(2, derived.getLong("id"));
        assertEquals(200, derived.getLong("balance"));
        assertFalse(derived.next());
      }
      try (ResultSet aliasedDerived = statement.executeQuery(
          "SELECT d.account_id AS selected_id, d.funds total FROM "
              + "(SELECT id AS account_id, balance AS funds FROM accounts) d "
              + "WHERE d.funds >= 100 AND d.funds < 300 "
              + "ORDER BY selected_id")) {
        assertEquals("selected_id",
            aliasedDerived.getMetaData().getColumnName(1));
        assertEquals("total", aliasedDerived.getMetaData().getColumnName(2));
        assertTrue(aliasedDerived.next());
        assertEquals(1, aliasedDerived.getLong("selected_id"));
        assertEquals(100, aliasedDerived.getLong("total"));
        assertTrue(aliasedDerived.next());
        assertEquals(2, aliasedDerived.getLong("selected_id"));
        assertEquals(200, aliasedDerived.getLong("total"));
        assertFalse(aliasedDerived.next());
      }
      try (ResultSet nestedAlias = statement.executeQuery(
          "SELECT second.final_id FROM "
              + "(SELECT first.account_id AS final_id FROM "
              + "(SELECT id AS account_id FROM accounts) first) second "
              + "ORDER BY final_id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(nestedAlias.next());
          assertEquals(expected, nestedAlias.getLong("final_id"));
        }
        assertFalse(nestedAlias.next());
      }
      try (ResultSet nullableAlias = statement.executeQuery(
          "SELECT d.missing AS outer_missing FROM "
              + "(SELECT id, NULL AS missing FROM accounts WHERE id=1) d")) {
        assertTrue(nullableAlias.next());
        assertNull(nullableAlias.getObject("outer_missing"));
        assertTrue(nullableAlias.wasNull());
        assertFalse(nullableAlias.next());
      }
      try (ResultSet scalar = statement.executeQuery(
          "SELECT id, balance FROM accounts WHERE region=7 AND balance="
              + "(SELECT balance FROM accounts WHERE accounts.id=2)")) {
        assertTrue(scalar.next());
        assertEquals(2, scalar.getLong("id"));
        assertEquals(200, scalar.getLong("balance"));
        assertFalse(scalar.next());
      }
      try (ResultSet scalar = statement.executeQuery(
          "SELECT id FROM accounts WHERE balance="
              + "(SELECT balance FROM accounts WHERE id=99)")) {
        assertFalse(scalar.next());
      }
      SQLException cardinality = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT id FROM accounts WHERE balance="
                  + "(SELECT region FROM accounts WHERE region=7)"));
      assertEquals("21000", cardinality.getSQLState());
      for (int depth : new int[] {3, 8, 32}) {
        try (ResultSet nestedScalar = statement.executeQuery(
            nestedScalarQuery(depth))) {
          assertTrue(nestedScalar.next());
          assertEquals(1, nestedScalar.getLong(1));
          assertFalse(nestedScalar.next());
        }
      }
      SQLException scalarDepthFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(nestedScalarQuery(33)));
      assertEquals("54001", scalarDepthFailure.getSQLState());
      for (int depth : new int[] {3, 8, 32}) {
        try (ResultSet nestedExistence = statement.executeQuery(
            nestedExistenceQuery(depth))) {
          for (long expected = 1; expected <= 3; expected++) {
            assertTrue(nestedExistence.next());
            assertEquals(expected, nestedExistence.getLong(1));
          }
          assertFalse(nestedExistence.next());
        }
      }
      try (ResultSet nestedNotExistence = statement.executeQuery(
          "SELECT id FROM accounts WHERE NOT EXISTS "
              + "(SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM accounts WHERE id=99)) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(nestedNotExistence.next());
          assertEquals(expected, nestedNotExistence.getLong(1));
        }
        assertFalse(nestedNotExistence.next());
      }
      SQLException existenceDepthFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(nestedExistenceQuery(33)));
      assertEquals("54001", existenceDepthFailure.getSQLState());
      for (int depth : new int[] {3, 8, 32}) {
        try (ResultSet nestedMembership = statement.executeQuery(
            nestedMembershipQuery(depth))) {
          assertTrue(nestedMembership.next());
          assertEquals(1, nestedMembership.getLong(1));
          assertFalse(nestedMembership.next());
        }
      }
      try (ResultSet nestedNotMembership = statement.executeQuery(
          "SELECT id FROM accounts WHERE id NOT IN "
              + "(SELECT id FROM accounts WHERE id IN "
              + "(SELECT NULL FROM accounts WHERE id=1)) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(nestedNotMembership.next());
          assertEquals(expected, nestedNotMembership.getLong(1));
        }
        assertFalse(nestedNotMembership.next());
      }
      SQLException membershipDepthFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(nestedMembershipQuery(33)));
      assertEquals("54001", membershipDepthFailure.getSQLState());
      try (ResultSet mixedScalar = statement.executeQuery(
          "SELECT id FROM accounts WHERE id="
              + "(SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM accounts WHERE id=1) LIMIT 1)")) {
        assertTrue(mixedScalar.next());
        assertEquals(1, mixedScalar.getLong(1));
        assertFalse(mixedScalar.next());
      }
      try (ResultSet mixedExistence = statement.executeQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM accounts WHERE id IN "
              + "(SELECT id FROM accounts WHERE id="
              + "(SELECT id FROM accounts WHERE id=1))) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(mixedExistence.next());
          assertEquals(expected, mixedExistence.getLong(1));
        }
        assertFalse(mixedExistence.next());
      }
      try (ResultSet mixedNull = statement.executeQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM accounts WHERE id IN "
              + "(SELECT NULL FROM accounts WHERE id=1))")) {
        assertFalse(mixedNull.next());
      }
      SQLException mixedCardinalityFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT id FROM accounts WHERE id="
                  + "(SELECT id FROM accounts WHERE region IN "
                  + "(SELECT id FROM regions))"));
      assertEquals("21000", mixedCardinalityFailure.getSQLState());
      try (ResultSet nonImmediateExistence = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE EXISTS "
              + "(SELECT b.id FROM accounts b WHERE b.id IN "
              + "(SELECT c.id FROM accounts c WHERE c.id=a.id)) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(nonImmediateExistence.next());
          assertEquals(expected, nonImmediateExistence.getLong(1));
        }
        assertFalse(nonImmediateExistence.next());
      }
      try (ResultSet nonImmediateScalar = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id="
              + "(SELECT b.id FROM accounts b WHERE EXISTS "
              + "(SELECT c.id FROM accounts c WHERE c.id=a.id) LIMIT 1) "
              + "ORDER BY id")) {
        assertTrue(nonImmediateScalar.next());
        assertEquals(1, nonImmediateScalar.getLong(1));
        assertFalse(nonImmediateScalar.next());
      }
      try (ResultSet nonImmediateMembership = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id IN "
              + "(SELECT b.id FROM accounts b WHERE b.id="
              + "(SELECT c.id FROM accounts c WHERE c.id=a.id)) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(nonImmediateMembership.next());
          assertEquals(expected, nonImmediateMembership.getLong(1));
        }
        assertFalse(nonImmediateMembership.next());
      }
      try (ResultSet nonImmediateShadow = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE EXISTS "
              + "(SELECT b.id FROM accounts b WHERE b.id IN "
              + "(SELECT a.id FROM accounts a WHERE a.id=3)) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(nonImmediateShadow.next());
          assertEquals(expected, nonImmediateShadow.getLong(1));
        }
        assertFalse(nonImmediateShadow.next());
      }
      try (ResultSet intermediateExistence = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id IN "
              + "(SELECT b.id FROM accounts b WHERE EXISTS "
              + "(SELECT c.id FROM accounts c "
              + "WHERE c.id=b.id AND c.region=8))")) {
        assertTrue(intermediateExistence.next());
        assertEquals(3, intermediateExistence.getLong(1));
        assertFalse(intermediateExistence.next());
      }
      try (ResultSet intermediateScalar = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id IN "
              + "(SELECT b.id FROM accounts b WHERE b.id="
              + "(SELECT c.id FROM accounts c WHERE c.id=b.id)) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(intermediateScalar.next());
          assertEquals(expected, intermediateScalar.getLong(1));
        }
        assertFalse(intermediateScalar.next());
      }
      try (ResultSet intermediateMembership = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id IN "
              + "(SELECT b.id FROM accounts b WHERE b.region IN "
              + "(SELECT c.region FROM accounts c WHERE c.id=b.id)) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(intermediateMembership.next());
          assertEquals(expected, intermediateMembership.getLong(1));
        }
        assertFalse(intermediateMembership.next());
      }
      try (ResultSet mixedIntermediate = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id IN "
              + "(SELECT b.id FROM accounts b WHERE EXISTS "
              + "(SELECT c.id FROM accounts c "
              + "WHERE c.id=b.id AND c.region=a.region)) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(mixedIntermediate.next());
          assertEquals(expected, mixedIntermediate.getLong(1));
        }
        assertFalse(mixedIntermediate.next());
      }
      try (ResultSet intermediateShadow = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id IN "
              + "(SELECT a.id FROM accounts a WHERE EXISTS "
              + "(SELECT c.id FROM accounts c "
              + "WHERE c.id=a.id AND c.region=8)) ORDER BY id")) {
        assertTrue(intermediateShadow.next());
        assertEquals(3, intermediateShadow.getLong(1));
        assertFalse(intermediateShadow.next());
      }
      SQLException intermediateCardinalityFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT a.id FROM accounts a WHERE a.id IN "
                  + "(SELECT b.id FROM accounts b WHERE b.region="
                  + "(SELECT c.region FROM accounts c "
                  + "WHERE c.region=b.region))"));
      assertEquals("21000", intermediateCardinalityFailure.getSQLState());
      try (ResultSet exists = statement.executeQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM regions WHERE code=7000) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(exists.next());
          assertEquals(expected, exists.getLong("id"));
        }
        assertFalse(exists.next());
      }
      try (ResultSet notExists = statement.executeQuery(
          "SELECT id FROM accounts WHERE NOT EXISTS "
              + "(SELECT id FROM regions WHERE code=7000)")) {
        assertFalse(notExists.next());
      }
      try (ResultSet correlated = statement.executeQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM regions "
              + "WHERE regions.id=accounts.region AND regions.code=7000) "
              + "ORDER BY balance")) {
        assertTrue(correlated.next());
        assertEquals(1, correlated.getLong(1));
        assertTrue(correlated.next());
        assertEquals(2, correlated.getLong(1));
        assertFalse(correlated.next());
      }
      try (ResultSet correlated = statement.executeQuery(
          "SELECT id FROM accounts WHERE NOT EXISTS "
              + "(SELECT id FROM regions "
              + "WHERE regions.id=accounts.region AND regions.code=7000)")) {
        assertTrue(correlated.next());
        assertEquals(3, correlated.getLong(1));
        assertFalse(correlated.next());
      }
      try (ResultSet correlated = statement.executeQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT id FROM regions "
              + "WHERE regions.id=accounts.region) ORDER BY balance")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(correlated.next());
          assertEquals(expected, correlated.getLong(1));
        }
        assertFalse(correlated.next());
      }
      try (ResultSet correlatedPrimary = statement.executeQuery(
          "SELECT id FROM accounts WHERE id="
              + "(SELECT id FROM region_labels "
              + "WHERE region_labels.id=accounts.id) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(correlatedPrimary.next());
          assertEquals(expected, correlatedPrimary.getLong(1));
        }
        assertFalse(correlatedPrimary.next());
      }
      try (ResultSet correlated = statement.executeQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT id FROM regions "
              + "WHERE regions.id=accounts.region AND regions.code=8000)")) {
        assertTrue(correlated.next());
        assertEquals(3, correlated.getLong(1));
        assertFalse(correlated.next());
      }
      try (ResultSet correlatedCardinality = statement.executeQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT region FROM region_labels "
              + "WHERE region_labels.region=accounts.region)")) {
        SQLException cardinalityFailure = assertThrows(
            SQLException.class, correlatedCardinality::next);
        assertEquals("21000", cardinalityFailure.getSQLState());
      }
      try (ResultSet correlated = statement.executeQuery(
          "SELECT id FROM accounts WHERE region IN "
              + "(SELECT id FROM regions "
              + "WHERE regions.id=accounts.region AND regions.code=7000) "
              + "ORDER BY balance")) {
        assertTrue(correlated.next());
        assertEquals(1, correlated.getLong(1));
        assertTrue(correlated.next());
        assertEquals(2, correlated.getLong(1));
        assertFalse(correlated.next());
      }
      try (ResultSet correlated = statement.executeQuery(
          "SELECT id FROM accounts WHERE region NOT IN "
              + "(SELECT id FROM regions "
              + "WHERE regions.id=accounts.region AND regions.code=7000)")) {
        assertTrue(correlated.next());
        assertEquals(3, correlated.getLong(1));
        assertFalse(correlated.next());
      }
      try (ResultSet correlatedUnknown = statement.executeQuery(
          "SELECT id FROM accounts WHERE region NOT IN "
              + "(SELECT NULL FROM regions "
              + "WHERE regions.id=accounts.region)")) {
        assertFalse(correlatedUnknown.next());
      }
      try (ResultSet selfCorrelated = statement.executeQuery(
          "SELECT a.id FROM accounts AS a WHERE EXISTS "
              + "(SELECT b.id FROM accounts b "
              + "WHERE b.region=a.region AND b.id=3)")) {
        assertTrue(selfCorrelated.next());
        assertEquals(3, selfCorrelated.getLong(1));
        assertFalse(selfCorrelated.next());
      }
      try (ResultSet selfCorrelated = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.id="
              + "(SELECT b.id FROM accounts AS b WHERE b.id=a.id) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(selfCorrelated.next());
          assertEquals(expected, selfCorrelated.getLong(1));
        }
        assertFalse(selfCorrelated.next());
      }
      try (ResultSet selfCorrelated = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE a.region IN "
              + "(SELECT b.region FROM accounts b WHERE b.id=a.id) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(selfCorrelated.next());
          assertEquals(expected, selfCorrelated.getLong(1));
        }
        assertFalse(selfCorrelated.next());
      }
      try (ResultSet shadowed = statement.executeQuery(
          "SELECT a.id FROM accounts a WHERE EXISTS "
              + "(SELECT a.id FROM accounts a WHERE a.id=a.id) "
              + "ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(shadowed.next());
          assertEquals(expected, shadowed.getLong(1));
        }
        assertFalse(shadowed.next());
      }
      String nested = "SELECT id FROM accounts";
      for (int depth = 1; depth < 32; depth++) {
        nested = "SELECT d" + depth + ".id FROM (" + nested + ") d" + depth;
      }
      nested = "SELECT overflow.id FROM (" + nested + ") overflow";
      String tooDeep = nested;
      SQLException depthFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(tooDeep));
      assertEquals("54001", depthFailure.getSQLState());
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX accounts_balance ON accounts(balance)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX accounts_region ON accounts(region)"));

      try (ResultSet ordered = statement.executeQuery(
          "SELECT id, balance FROM accounts ORDER BY balance")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(ordered.next());
          assertEquals(expected, ordered.getLong("id"));
          assertEquals(expected * 100, ordered.getLong("balance"));
        }
        assertFalse(ordered.next());
      }
      try (ResultSet grouped = statement.executeQuery(
          "SELECT region AS area, COUNT(*) FROM accounts "
              + "WHERE balance >= 150 AND balance < 350 "
              + "GROUP BY region ORDER BY area")) {
        assertEquals("area", grouped.getMetaData().getColumnLabel(1));
        assertEquals("count", grouped.getMetaData().getColumnLabel(2));
        assertTrue(grouped.next());
        assertEquals(7, grouped.getLong("area"));
        assertEquals(1, grouped.getLong("count"));
        assertTrue(grouped.next());
        assertEquals(8, grouped.getLong(1));
        assertEquals(1, grouped.getLong(2));
        assertFalse(grouped.next());
      }
      try (ResultSet grouped = statement.executeQuery(
          "SELECT region AS area, COUNT(*) FROM accounts "
              + "GROUP BY region HAVING COUNT(*) > 1 ORDER BY area")) {
        assertTrue(grouped.next());
        assertEquals(7, grouped.getLong("area"));
        assertEquals(2, grouped.getLong("count"));
        assertFalse(grouped.next());
      }
      try (ResultSet distinct = statement.executeQuery(
          "SELECT DISTINCT region AS area FROM accounts "
              + "WHERE balance >= 150 AND balance < 350 "
              + "ORDER BY area")) {
        assertTrue(distinct.next());
        assertEquals(7, distinct.getLong("area"));
        assertTrue(distinct.next());
        assertEquals(8, distinct.getLong(1));
        assertFalse(distinct.next());
      }
      try (ResultSet joined = statement.executeQuery(
          "SELECT accounts.id AS account_id, regions.code region_code FROM accounts "
              + "JOIN regions ON accounts.region=regions.id "
              + "WHERE accounts.id >= 1 AND accounts.id < 4 "
              + "AND accounts.region=7 LIMIT 2")) {
        assertEquals("account_id", joined.getMetaData().getColumnLabel(1));
        assertEquals("region_code", joined.getMetaData().getColumnLabel(2));
        assertTrue(joined.next());
        long firstId = joined.getLong("account_id");
        assertEquals(7000, joined.getLong("region_code"));
        assertTrue(joined.next());
        long secondId = joined.getLong("account_id");
        assertEquals(7000, joined.getLong("region_code"));
        assertEquals(3, firstId + secondId);
        assertEquals(2, firstId * secondId);
        assertFalse(joined.next());
      }
      try (ResultSet joined = statement.executeQuery(
          "SELECT accounts.id, region_labels.code FROM accounts "
              + "JOIN region_labels ON accounts.region=region_labels.region "
              + "WHERE accounts.id=1")) {
        assertTrue(joined.next());
        long firstCode = joined.getLong("code");
        assertEquals(1, joined.getLong("id"));
        assertTrue(joined.next());
        long secondCode = joined.getLong("code");
        assertEquals(1, joined.getLong("id"));
        assertEquals(14003, firstCode + secondCode);
        assertEquals(49021002, firstCode * secondCode);
        assertFalse(joined.next());
      }
      try (ResultSet joined = statement.executeQuery(
          "SELECT accounts.id, region_labels.code FROM accounts "
              + "JOIN region_labels ON accounts.region=region_labels.region "
              + "WHERE accounts.id=1 AND region_labels.code=7002")) {
        assertTrue(joined.next());
        assertEquals(1, joined.getLong("id"));
        assertEquals(7002, joined.getLong("code"));
        assertFalse(joined.next());
      }

      try (ResultSet rows = statement.executeQuery(
          "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4")) {
        ResultSetMetaData metadata = rows.getMetaData();
        assertEquals(2, metadata.getColumnCount());
        assertEquals(Types.BIGINT, metadata.getColumnType(1));
        assertEquals("id", metadata.getColumnLabel(1));
        assertEquals("balance", metadata.getColumnLabel(2));
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertEquals(100, rows.getLong("balance"));
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
        assertEquals(200L, rows.getObject(2, Long.class));
        assertTrue(rows.next());
        assertEquals("3", rows.getString(1));
        assertEquals(300, rows.getLong(2));
        assertFalse(rows.next());
        assertTrue(rows.isAfterLast());
      }
      try (ResultSet aggregate = statement.executeQuery(
          "SELECT COUNT(*) FROM accounts WHERE region=7")) {
        assertEquals("count", aggregate.getMetaData().getColumnLabel(1));
        assertTrue(aggregate.next());
        assertEquals(2, aggregate.getLong("count"));
        assertFalse(aggregate.next());
      }

      connection.setAutoCommit(false);
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO accounts VALUES (4, 400, 9)"));
      connection.rollback();
      try (ResultSet rolledBack = statement.executeQuery(
          "SELECT balance FROM accounts WHERE id=4")) {
        assertFalse(rolledBack.next());
      }
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO accounts VALUES (4, 450, 9)"));
      connection.commit();
      connection.setAutoCommit(true);
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(
            "SELECT balance FROM accounts WHERE id=4")) {
      assertTrue(row.next());
      assertEquals(450, row.getLong(1));
      assertFalse(row.next());
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement();
        ResultSet sequence = statement.executeQuery(
            "SELECT NEXT VALUE FOR jdbc_ids")) {
      assertTrue(sequence.next());
      assertEquals(104, sequence.getLong(1));
      assertFalse(sequence.next());
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(
            "SELECT rank FROM nullable_values WHERE id=2")) {
      assertTrue(row.next());
      assertNull(row.getObject(1));
      assertTrue(row.wasNull());
      assertFalse(row.next());
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
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

  @Test
  void readCommittedRefreshesEachNestedQueryStatement(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    String query = "SELECT id FROM isolation_values WHERE value="
        + "(SELECT value FROM isolation_values WHERE id=1) ORDER BY id";

    try (Connection setup = DriverManager.getConnection(url(server));
        Statement statement = setup.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE isolation_values "
              + "(id BIGINT PRIMARY KEY, value BIGINT)"));
      assertEquals(2, statement.executeUpdate(
          "INSERT INTO isolation_values VALUES (1, 10), (2, 20)"));
    }
    try (Connection reader = DriverManager.getConnection(url(server));
        Connection writer = DriverManager.getConnection(url(server));
        Statement reads = reader.createStatement();
        Statement writes = writer.createStatement()) {
      reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      reader.setAutoCommit(false);
      assertEquals(Connection.TRANSACTION_READ_COMMITTED,
          reader.getTransactionIsolation());
      assertQueryKeys(reads, query, 1);
      assertEquals(1, writes.executeUpdate(
          "UPDATE isolation_values SET value=20 WHERE id=1"));
      assertQueryKeys(reads, query, 1, 2);
      reader.rollback();

      reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      assertQueryKeys(reads, query, 1, 2);
      assertEquals(1, writes.executeUpdate(
          "UPDATE isolation_values SET value=30 WHERE id=1"));
      assertQueryKeys(reads, query, 1, 2);
      reader.rollback();
      reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      assertQueryKeys(reads, query, 1);
      reader.rollback();
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertQueryKeys(
      Statement statement,
      String query,
      long... expectedKeys) throws SQLException {
    try (ResultSet rows = statement.executeQuery(query)) {
      for (long expected : expectedKeys) {
        assertTrue(rows.next());
        assertEquals(expected, rows.getLong(1));
      }
      assertFalse(rows.next());
    }
  }

  @Test
  void reportsBoundedSubsetAndStableSqlStates(@TempDir Path root) throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    SQLException badUrl = assertThrows(
        SQLException.class,
        () -> DriverManager.getConnection("jdbc:river://localhost:not-a-port"));
    assertEquals("22000", badUrl.getSQLState());
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement()) {
      DatabaseMetaData metadata = connection.getMetaData();
      assertEquals("River", metadata.getDatabaseProductName());
      assertEquals("River JDBC", metadata.getDriverName());
      assertEquals(url(server), metadata.getURL());
      assertEquals(connection, metadata.getConnection());
      assertEquals(4, metadata.getJDBCMajorVersion());
      assertEquals(3, metadata.getJDBCMinorVersion());
      assertTrue(metadata.supportsTransactions());
      assertTrue(metadata.supportsTransactionIsolationLevel(
          Connection.TRANSACTION_READ_COMMITTED));
      assertTrue(metadata.supportsTransactionIsolationLevel(
          Connection.TRANSACTION_REPEATABLE_READ));
      assertTrue(metadata.supportsTransactionIsolationLevel(
          Connection.TRANSACTION_SERIALIZABLE));
      assertTrue(metadata.supportsBatchUpdates());
      assertTrue(metadata.supportsResultSetConcurrency(
          ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
      assertFalse(metadata.supportsResultSetConcurrency(
          ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
      assertEquals(8, metadata.getMaxColumnsInTable());
      assertEquals(64, metadata.getMaxTableNameLength());
      assertThrows(
          java.sql.SQLFeatureNotSupportedException.class,
          () -> metadata.getTables(null, null, null, null));
      SQLException secondStatement = assertThrows(
          SQLException.class,
          connection::createStatement);
      assertEquals("40001", secondStatement.getSQLState());
      SQLException invalidSql = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("NOT SQL"));
      assertEquals("22000", invalidSql.getSQLState());
      assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> {
        connection.setReadOnly(true);
      });
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void bigintComparisonsReachScansIndexesJoinsAggregatesAndMutations(
      @TempDir Path root) throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE comparison_values "
              + "(id BIGINT PRIMARY KEY, value BIGINT, kind BIGINT)"));
      assertEquals(
          6,
          statement.executeUpdate(
              "INSERT INTO comparison_values VALUES "
                  + "(1, -9223372036854775808, 1), (2, -1, 1), "
                  + "(3, 0, 2), (4, 1, 2), "
                  + "(5, 9223372036854775807, 2), (6, NULL, 2)"));

      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value<-1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value<=-1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value<=-9223372036854775808")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value>1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value>=1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value>9223372036854775807")) {
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value<>0 ORDER BY id")) {
        for (long expected : new long[] {1, 2, 4, 5}) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value!=0 ORDER BY id")) {
        for (long expected : new long[] {1, 2, 4, 5}) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value IN (-1, 1, 99) ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value NOT IN (-1, 1) ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(3, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value NOT IN (-1, NULL)")) {
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value IN (NULL)")) {
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE id IN "
              + "(SELECT id FROM comparison_values WHERE kind IN (1)) ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value>=-1 AND value<=1 ORDER BY id")) {
        for (long expected = 2; expected <= 4; expected++) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value BETWEEN -1 AND 1 ORDER BY id")) {
        for (long expected = 2; expected <= 4; expected++) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value BETWEEN 1 AND 9223372036854775807 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value>=-1 AND value<1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(3, rows.getLong(1));
        assertFalse(rows.next());
      }

      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE comparison_indexed "
              + "(id BIGINT PRIMARY KEY, value BIGINT)"));
      assertEquals(3, statement.executeUpdate(
          "INSERT INTO comparison_indexed VALUES (1, 100), (2, 200), (3, 300)"));
      assertEquals(0, statement.executeUpdate(
          "CREATE INDEX comparison_value_idx ON comparison_indexed(value)"));
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_indexed WHERE value>100 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(3, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_indexed "
              + "WHERE value BETWEEN 100 AND 200 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_indexed ORDER BY value DESC")) {
        for (long expected = 3; expected >= 1; expected--) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values ORDER BY value DESC")) {
        long[] expected = {5, 4, 3, 2, 1, 6};
        for (long id : expected) {
          assertTrue(rows.next());
          assertEquals(id, rows.getLong(1));
        }
        assertFalse(rows.next());
      }

      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(*) FROM comparison_values WHERE value<>0")) {
        assertTrue(count.next());
        assertEquals(4, count.getLong(1));
        assertFalse(count.next());
      }
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(value) AS present FROM comparison_values")) {
        assertEquals("present", count.getMetaData().getColumnLabel(1));
        assertTrue(count.next());
        assertEquals(5, count.getLong(1));
        assertFalse(count.wasNull());
        assertFalse(count.next());
      }
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(value) FROM comparison_values WHERE id>=5")) {
        assertEquals("count", count.getMetaData().getColumnLabel(1));
        assertTrue(count.next());
        assertEquals(1, count.getLong(1));
        assertFalse(count.wasNull());
        assertFalse(count.next());
      }
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(value) FROM comparison_values WHERE id=99")) {
        assertTrue(count.next());
        assertEquals(0, count.getLong(1));
        assertFalse(count.wasNull());
        assertFalse(count.next());
      }
      assertEquals(0, statement.executeUpdate(
          "CREATE INDEX comparison_kind_idx ON comparison_values(kind)"));
      try (ResultSet groups = statement.executeQuery(
          "SELECT kind, COUNT(*) FROM comparison_values "
              + "WHERE value!=0 GROUP BY kind ORDER BY kind")) {
        assertTrue(groups.next());
        assertEquals(1, groups.getLong(1));
        assertEquals(2, groups.getLong(2));
        assertTrue(groups.next());
        assertEquals(2, groups.getLong(1));
        assertEquals(2, groups.getLong(2));
        assertFalse(groups.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) AS total FROM comparison_values")) {
        assertEquals("total", sum.getMetaData().getColumnLabel(1));
        assertTrue(sum.next());
        assertEquals(-1, sum.getLong(1));
        assertFalse(sum.wasNull());
        assertFalse(sum.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) FROM comparison_values WHERE id>=2 AND id<5")) {
        assertEquals("sum", sum.getMetaData().getColumnLabel(1));
        assertTrue(sum.next());
        assertEquals(0, sum.getLong(1));
        assertFalse(sum.wasNull());
        assertFalse(sum.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) FROM comparison_values WHERE id=6")) {
        assertTrue(sum.next());
        assertEquals(0, sum.getLong(1));
        assertTrue(sum.wasNull());
        assertNull(sum.getObject(1));
        assertFalse(sum.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) FROM comparison_values WHERE id=99")) {
        assertTrue(sum.next());
        assertNull(sum.getObject(1));
        assertTrue(sum.wasNull());
        assertFalse(sum.next());
      }
      try (ResultSet minimum = statement.executeQuery(
          "SELECT MIN(value) AS lowest FROM comparison_values")) {
        assertEquals("lowest", minimum.getMetaData().getColumnLabel(1));
        assertTrue(minimum.next());
        assertEquals(Long.MIN_VALUE, minimum.getLong(1));
        assertFalse(minimum.wasNull());
        assertFalse(minimum.next());
      }
      try (ResultSet maximum = statement.executeQuery(
          "SELECT MAX(value) FROM comparison_values WHERE kind=1")) {
        assertEquals("max", maximum.getMetaData().getColumnLabel(1));
        assertTrue(maximum.next());
        assertEquals(-1, maximum.getLong(1));
        assertFalse(maximum.wasNull());
        assertFalse(maximum.next());
      }
      try (ResultSet minimum = statement.executeQuery(
          "SELECT MIN(value) FROM comparison_values WHERE id=6")) {
        assertTrue(minimum.next());
        assertNull(minimum.getObject(1));
        assertTrue(minimum.wasNull());
        assertFalse(minimum.next());
      }
      try (ResultSet maximum = statement.executeQuery(
          "SELECT MAX(value) FROM comparison_values WHERE id=99")) {
        assertTrue(maximum.next());
        assertNull(maximum.getObject(1));
        assertTrue(maximum.wasNull());
        assertFalse(maximum.next());
      }
      SQLException positiveOverflow = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT SUM(value) FROM comparison_values WHERE value>0"));
      assertEquals("22003", positiveOverflow.getSQLState());
      SQLException negativeOverflow = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT SUM(value) FROM comparison_values WHERE value<0"));
      assertEquals("22003", negativeOverflow.getSQLState());
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE comparison_kinds "
              + "(id BIGINT PRIMARY KEY, label BIGINT)"));
      assertEquals(2, statement.executeUpdate(
          "INSERT INTO comparison_kinds VALUES (1, 10), (2, 20)"));
      try (ResultSet rows = statement.executeQuery(
          "SELECT comparison_values.id FROM comparison_values "
              + "JOIN comparison_kinds "
              + "ON comparison_values.kind=comparison_kinds.id "
              + "WHERE comparison_values.value>0")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT cv.id, ck.label FROM comparison_values cv "
              + "JOIN comparison_kinds AS ck ON cv.kind=ck.id "
              + "WHERE cv.value>0")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertEquals(20, rows.getLong(2));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertEquals(20, rows.getLong(2));
        assertFalse(rows.next());
      }
      SQLException ambiguousAlias = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT cv.id FROM comparison_values cv "
                  + "JOIN comparison_kinds cv ON cv.kind=cv.id"));
      assertEquals("22000", ambiguousAlias.getSQLState());
      try (ResultSet rows = statement.executeQuery(
          "SELECT d.id FROM "
              + "(SELECT id, value FROM comparison_values) d "
              + "WHERE d.value>0 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT d.id FROM "
              + "(SELECT id, value FROM comparison_values) d "
              + "WHERE d.value>0 ORDER BY id DESC")) {
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet row = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE id="
              + "(SELECT id FROM comparison_values "
              + "WHERE value>=9223372036854775807)")) {
        assertTrue(row.next());
        assertEquals(5, row.getLong(1));
        assertFalse(row.next());
      }

      assertEquals(2, statement.executeUpdate(
          "UPDATE comparison_values SET kind=9 WHERE value<=-1"));
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(*) FROM comparison_values WHERE kind=9")) {
        assertTrue(count.next());
        assertEquals(2, count.getLong(1));
      }
      assertEquals(2, statement.executeUpdate(
          "DELETE FROM comparison_values WHERE value>=1"));
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value!=0 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
    }

    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void preparedStatementsRenderOnlyBoundedBigintParameters(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (Connection connection = DriverManager.getConnection(url(server));
        Statement schema = connection.createStatement()) {
      assertEquals(0, schema.executeUpdate(
          "CREATE TABLE prepared_values (id BIGINT PRIMARY KEY, value BIGINT)"));
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO prepared_values VALUES (?, ?)")) {
      insert.setLong(1, 1);
      SQLException unset = assertThrows(SQLException.class, insert::executeUpdate);
      assertEquals("22000", unset.getSQLState());
      insert.setLong(2, 100);
      assertEquals(1, insert.executeUpdate());
      insert.setObject(1, Integer.valueOf(2), Types.BIGINT);
      insert.setLong(2, 200);
      assertEquals(1, insert.executeUpdate());
      insert.setLong(1, 3);
      insert.setLong(2, Long.MIN_VALUE);
      assertEquals(1, insert.executeUpdate());
      assertThrows(SQLException.class, () -> insert.setString(1, "1 OR 1=1"));
      insert.clearParameters();
      assertThrows(SQLException.class, () -> insert.setLong(3, 3));
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement select = connection.prepareStatement(
            "SELECT value FROM prepared_values WHERE id=?")) {
      select.setLong(1, 2);
      try (ResultSet result = select.executeQuery()) {
        assertEquals(1, result.getMetaData().getColumnCount());
        assertEquals("value", result.getMetaData().getColumnName(1));
        assertTrue(result.next());
        assertEquals(200, result.getLong("value"));
        assertFalse(result.next());
      }
      select.setLong(1, 1);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next());
        assertEquals(100, result.getLong(1));
      }
      select.setLong(1, 3);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next());
        assertEquals(Long.MIN_VALUE, result.getLong(1));
      }
      assertThrows(
          SQLException.class,
          () -> select.executeQuery("SELECT value FROM prepared_values WHERE id=2"));
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement select = connection.prepareStatement(
            "SELECT id FROM prepared_values WHERE value>=? ORDER BY id")) {
      select.setLong(1, 100);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next());
        assertEquals(1, result.getLong(1));
        assertTrue(result.next());
        assertEquals(2, result.getLong(1));
        assertFalse(result.next());
      }
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement select = connection.prepareStatement(
            "SELECT id FROM prepared_values WHERE id IN (?, ?) ORDER BY id")) {
      select.setLong(1, 3);
      select.setLong(2, 1);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next());
        assertEquals(1, result.getLong(1));
        assertTrue(result.next());
        assertEquals(3, result.getLong(1));
        assertFalse(result.next());
      }
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement select = connection.prepareStatement(
            "SELECT id FROM prepared_values WHERE value BETWEEN ? AND ? ORDER BY id")) {
      select.setLong(1, 100);
      select.setLong(2, 200);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next());
        assertEquals(1, result.getLong(1));
        assertTrue(result.next());
        assertEquals(2, result.getLong(1));
        assertFalse(result.next());
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void jdbcCarriesVarcharMetadataValuesAndPreparedParameters(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (Connection connection = DriverManager.getConnection(url(server))) {
      try (Statement schema = connection.createStatement()) {
        assertEquals(0, schema.executeUpdate(
            "CREATE TABLE text_values "
                + "(id BIGINT PRIMARY KEY, label VARCHAR(7), "
                + "state VARCHAR(7) DEFAULT 'new')"));
        assertEquals(0, schema.executeUpdate(
            "CREATE UNIQUE INDEX text_values_label ON text_values(label)"));
        assertEquals(1, schema.executeUpdate(
            "INSERT INTO text_values (id, label) VALUES (3, NULL)"));
      }

      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO text_values (id, label) VALUES (?, ?)")) {
        insert.setLong(1, 1);
        insert.setString(2, "it's");
        assertEquals(1, insert.executeUpdate());
        insert.setLong(1, 2);
        insert.setObject(2, "alpha", Types.VARCHAR);
        assertEquals(1, insert.executeUpdate());
        assertThrows(SQLException.class, () -> insert.setString(2, "too long"));
      }

      try (PreparedStatement select = connection.prepareStatement(
          "SELECT id, label, state FROM text_values WHERE label=?")) {
        select.setString(1, "it's");
        try (ResultSet rows = select.executeQuery()) {
          ResultSetMetaData metadata = rows.getMetaData();
          assertEquals(Types.BIGINT, metadata.getColumnType(1));
          assertEquals(Types.VARCHAR, metadata.getColumnType(2));
          assertEquals("VARCHAR", metadata.getColumnTypeName(2));
          assertEquals(String.class.getName(), metadata.getColumnClassName(2));
          assertTrue(metadata.isCaseSensitive(2));
          assertFalse(metadata.isSigned(2));
          assertEquals(7, metadata.getPrecision(2));
          assertTrue(rows.next());
          assertEquals(1, rows.getLong("id"));
          assertEquals("it's", rows.getString("label"));
          assertEquals("it's", rows.getObject("label"));
          assertEquals("it's", rows.getObject("label", String.class));
          assertEquals("new", rows.getString("state"));
          assertThrows(SQLException.class, () -> rows.getLong("label"));
          assertFalse(rows.next());
        }
      }

      try (Statement statement = connection.createStatement();
          ResultSet nullable = statement.executeQuery(
              "SELECT label FROM text_values WHERE id=3")) {
        assertTrue(nullable.next());
        assertNull(nullable.getString(1));
        assertTrue(nullable.wasNull());
        assertNull(nullable.getObject(1));
        assertTrue(nullable.wasNull());
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void batchesAreBoundedAndReportTheSuccessfulPrefix(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (Connection connection = DriverManager.getConnection(url(server))) {
      try (Statement statement = connection.createStatement()) {
        assertEquals(0, statement.executeUpdate(
            "CREATE TABLE batch_values (id BIGINT PRIMARY KEY, value BIGINT)"));
        statement.addBatch("INSERT INTO batch_values VALUES (1, 10)");
        statement.addBatch("INSERT INTO batch_values VALUES (2, 20)");
        assertTrue(Arrays.equals(new int[] {1, 1}, statement.executeBatch()));
        assertEquals(0, statement.executeBatch().length);

        statement.addBatch("INSERT INTO batch_values VALUES (3, 30)");
        statement.addBatch("INSERT INTO batch_values VALUES (1, 999)");
        statement.addBatch("INSERT INTO batch_values VALUES (4, 40)");
        BatchUpdateException partial = assertThrows(
            BatchUpdateException.class,
            statement::executeBatch);
        assertTrue(Arrays.equals(new int[] {1}, partial.getUpdateCounts()));
        assertEquals("40001", partial.getSQLState());

        for (int index = 0;
            index < RiverJdbcStatement.MAXIMUM_BATCH_STATEMENTS;
            index++) {
          statement.addBatch("INSERT INTO batch_values VALUES (99, 99)");
        }
        SQLException full = assertThrows(
            SQLException.class,
            () -> statement.addBatch("INSERT INTO batch_values VALUES (100, 100)"));
        assertEquals("53000", full.getSQLState());
        statement.clearBatch();
      }

      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO batch_values VALUES (?, ?)")) {
        insert.setLong(1, 4);
        insert.setLong(2, 40);
        insert.addBatch();
        insert.setLong(1, 5);
        insert.setLong(2, 50);
        insert.addBatch();
        assertTrue(Arrays.equals(new int[] {1, 1}, insert.executeBatch()));
      }

      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery(
              "SELECT id, value FROM batch_values WHERE id >= 1 AND id < 6")) {
        long[] expectedKeys = {1, 2, 3, 4, 5};
        int index = 0;
        while (rows.next()) {
          assertEquals(expectedKeys[index++], rows.getLong(1));
        }
        assertEquals(expectedKeys.length, index);
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void reportsCheckConstraintSqlState(@TempDir Path root) throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE checked_values "
                  + "(id BIGINT PRIMARY KEY, value BIGINT CHECK (value >= 0))"));
      SQLException violation = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate(
              "INSERT INTO checked_values VALUES (1, -1)"));
      assertEquals("23514", violation.getSQLState());
      assertEquals(StatusCode.CHECK_VIOLATION.stableCode(), violation.getErrorCode());
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO checked_values VALUES (1, 1)"));
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void enforcesUniqueColumnsThroughJdbc(@TempDir Path root) throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE unique_values "
                  + "(id BIGINT PRIMARY KEY, value BIGINT UNIQUE)"));
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO unique_values VALUES (1, 10)"));
      SQLException violation = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("INSERT INTO unique_values VALUES (2, 10)"));
      assertEquals("23505", violation.getSQLState());
      assertEquals(StatusCode.UNIQUE_VIOLATION.stableCode(), violation.getErrorCode());
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM unique_values WHERE value=10")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void reportsForeignKeySqlState(@TempDir Path root) throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE fk_parents (id BIGINT PRIMARY KEY, value BIGINT)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE fk_children "
                  + "(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES fk_parents(id))"));
      SQLException violation = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate(
              "INSERT INTO fk_children VALUES (1, 99)"));
      assertEquals("23503", violation.getSQLState());
      assertEquals(StatusCode.FOREIGN_KEY_VIOLATION.stableCode(), violation.getErrorCode());
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO fk_parents VALUES (99, 10)"));
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO fk_children VALUES (1, 99)"));
      SQLException deleteViolation = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("DELETE FROM fk_parents WHERE id=99"));
      assertEquals("23503", deleteViolation.getSQLState());
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void returnsIdentityKeysThroughJdbc(@TempDir Path root) throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE generated_events "
                  + "(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                  + "payload BIGINT)"));
      assertEquals(
          1,
          statement.executeUpdate(
              "INSERT INTO generated_events(payload) VALUES (10)",
              Statement.RETURN_GENERATED_KEYS));
      try (ResultSet keys = statement.getGeneratedKeys()) {
        assertTrue(keys.next());
        assertEquals(1, keys.getLong(1));
        assertEquals(1L, keys.getObject("GENERATED_KEY", Long.class));
        assertTrue(keys.getMetaData().isAutoIncrement(1));
        assertFalse(keys.next());
      }

      connection.setAutoCommit(false);
      assertEquals(
          1,
          statement.executeUpdate(
              "INSERT INTO generated_events(payload) VALUES (20)",
              Statement.RETURN_GENERATED_KEYS));
      try (ResultSet keys = statement.getGeneratedKeys()) {
        assertTrue(keys.next());
        assertEquals(2, keys.getLong(1));
      }
      connection.rollback();
      connection.setAutoCommit(true);
      assertFalse(statement.execute(
          "INSERT INTO generated_events(payload) VALUES (30)",
          Statement.RETURN_GENERATED_KEYS));
      try (ResultSet keys = statement.getGeneratedKeys()) {
        assertTrue(keys.next());
        assertEquals(3, keys.getLong(1));
        assertFalse(keys.next());
      }
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE generated_events SET payload=31 WHERE id=3",
              Statement.RETURN_GENERATED_KEYS));
      try (ResultSet keys = statement.getGeneratedKeys()) {
        assertFalse(keys.next());
      }
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO generated_events(payload) VALUES (?)",
            Statement.RETURN_GENERATED_KEYS)) {
      insert.setLong(1, 40);
      assertEquals(1, insert.executeUpdate());
      try (ResultSet keys = insert.getGeneratedKeys()) {
        assertTrue(keys.next());
        assertEquals(4, keys.getLong(1));
        assertFalse(keys.next());
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void dataSourceExecutesJdbcInsideTlsBoundTokenAuthentication(@TempDir Path root)
      throws Exception {
    byte[] token = "river-jdbc-auth-token-0001".getBytes(StandardCharsets.UTF_8);
    TokenAuthenticatorOpenResult authenticator = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(token, token.length, authenticator));
    SSLContext serverContext = TestTlsContexts.server();
    SSLContext clientContext = TestTlsContexts.trustedClient();
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = startAuthenticated(
        database, serverContext, authenticator.authenticator());

    RiverDataSource source = new RiverDataSource();
    source.setPort(server.port());
    assertEquals(5, source.getLoginTimeout());
    source.setLoginTimeout(5);
    assertThrows(
        java.sql.SQLFeatureNotSupportedException.class,
        () -> source.setLoginTimeout(0));
    source.setAuthentication(clientContext, token, token.length);
    Arrays.fill(token, (byte) 0);
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE secure_jdbc (id BIGINT PRIMARY KEY, value BIGINT)"));
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO secure_jdbc VALUES (1, 700)"));
      try (ResultSet result = statement.executeQuery(
          "SELECT value FROM secure_jdbc WHERE id=1")) {
        assertTrue(result.next());
        assertEquals(700, result.getLong("value"));
      }
    }
    source.close();
    SQLException closed = assertThrows(SQLException.class, source::getConnection);
    assertEquals("08003", closed.getSQLState());

    byte[] wrongToken =
        "wrong-jdbc-auth-token-0001".getBytes(StandardCharsets.UTF_8);
    RiverDataSource wrong = new RiverDataSource();
    wrong.setPort(server.port());
    wrong.setAuthentication(clientContext, wrongToken, wrongToken.length);
    SQLException rejected = assertThrows(SQLException.class, wrong::getConnection);
    assertEquals("28000", rejected.getSQLState());
    wrong.close();
    Arrays.fill(wrongToken, (byte) 0);

    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, result));
    return result.server();
  }

  private static LoopbackRiverServer startAuthenticated(
      RiverDatabase database,
      SSLContext context,
      TokenAuthenticator authenticator) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database, 0, context, authenticator, result));
    return result.server();
  }

  private static String url(LoopbackRiverServer server) {
    return RiverDriver.URL_PREFIX + server.port();
  }
}
