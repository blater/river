package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDriverStreamingSqlTest {
  @Test
  void driverManagerExecutesStreamingSqlTransactionsAndDurableReopen(@TempDir java.nio.file.Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
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
              + "WHERE regions.id=nullable_values.rank) ORDER BY id")) {
        assertTrue(correlatedNull.next());
        assertEquals(2, correlatedNull.getLong(1));
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
          () -> {
            try (ResultSet rows = statement.executeQuery(
                "SELECT id FROM accounts WHERE balance="
                    + "(SELECT region FROM accounts WHERE region=7)")) {
              rows.next();
            }
          });
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
          () -> {
            try (ResultSet rows = statement.executeQuery(
                "SELECT id FROM accounts WHERE id="
                    + "(SELECT id FROM accounts WHERE region IN "
                    + "(SELECT id FROM regions))")) {
              rows.next();
            }
          });
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
          () -> {
            try (ResultSet rows = statement.executeQuery(
                "SELECT a.id FROM accounts a WHERE a.id IN "
                    + "(SELECT b.id FROM accounts b WHERE b.region="
                    + "(SELECT c.region FROM accounts c "
                    + "WHERE c.region=b.region))")) {
              rows.next();
            }
          });
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
      SQLException cardinalityFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
          "SELECT id FROM accounts WHERE region="
              + "(SELECT region FROM region_labels "
              + "WHERE region_labels.region=accounts.region)"));
      assertEquals("21000", cardinalityFailure.getSQLState());
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
      fixture.reopen();
    try (Connection connection = DriverManager.getConnection(fixture.url());
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(
            "SELECT balance FROM accounts WHERE id=4")) {
      assertTrue(row.next());
      assertEquals(450, row.getLong(1));
      assertFalse(row.next());
    }
    try (Connection connection = DriverManager.getConnection(fixture.url());
        Statement statement = connection.createStatement();
        ResultSet sequence = statement.executeQuery(
            "SELECT NEXT VALUE FOR jdbc_ids")) {
      assertTrue(sequence.next());
      assertEquals(104, sequence.getLong(1));
      assertFalse(sequence.next());
    }
    try (Connection connection = DriverManager.getConnection(fixture.url());
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(
            "SELECT rank FROM nullable_values WHERE id=2")) {
      assertTrue(row.next());
      assertNull(row.getObject(1));
      assertTrue(row.wasNull());
      assertFalse(row.next());
    }

    }
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

}
