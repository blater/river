package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlTableConstraintParserTest {
  private final SqlParser parser = new SqlParser();
  private final SqlCommand command = new SqlCommand();

  @Test
  void retainsNamedCompositeConstraintPartsAndCheckReferenceSets() {
    assertEquals(StatusCode.OK, parser.parse(
        "CREATE TABLE membership (tenant BIGINT, member BIGINT, parent_tenant BIGINT, "
            + "parent_member BIGINT, CONSTRAINT pk_membership PRIMARY KEY (tenant, member), "
            + "CONSTRAINT uq_parent UNIQUE (parent_tenant, parent_member), "
            + "CONSTRAINT fk_parent FOREIGN KEY (parent_tenant, parent_member) "
            + "REFERENCES parent (tenant, member), "
            + "CONSTRAINT ck_order CHECK (tenant < member))",
        command));
    assertEquals(4, command.tableConstraintCount());
    assertConstraint(0, SqlCommand.CONSTRAINT_PRIMARY_KEY, "pk_membership", "tenant", "member");
    assertConstraint(1, SqlCommand.CONSTRAINT_UNIQUE, "uq_parent",
        "parent_tenant", "parent_member");
    assertConstraint(2, SqlCommand.CONSTRAINT_FOREIGN_KEY, "fk_parent",
        "parent_tenant", "parent_member");
    assertEquals("parent", command.tableConstraintReferenceTableName(2).toString());
    assertEquals("tenant", command.tableConstraintReferencePartName(2, 0).toString());
    assertConstraint(3, SqlCommand.CONSTRAINT_CHECK, "ck_order", "tenant", "member");
  }

  @Test
  void acceptsOneColumnNoPrimaryAndConstantCheck() {
    assertEquals(StatusCode.OK, parser.parse("CREATE TABLE singleton (value VARCHAR(7))", command));
    assertEquals(0, command.tableConstraintCount());
    assertEquals(StatusCode.OK,
        parser.parse("CREATE TABLE always_valid (value BIGINT, CHECK (1 = 1))", command));
    assertEquals(1, command.tableConstraintCount());
    assertEquals(0, command.tableConstraintPartCount(0));
  }

  @Test
  void acceptsTpcCWidthsAndNormalizesCharacterToTextLane() {
    assertEquals(StatusCode.OK, parser.parse(
        "CREATE TABLE customer (c_data VARCHAR(500), c_state CHAR(2), "
            + "c_zip CHARACTER(9))", command));
    assertEquals(SqlTypeDescriptor.varchar(500), command.columnTypeDescriptor(0));
    assertEquals(SqlTypeDescriptor.varchar(2), command.columnTypeDescriptor(1));
    assertEquals(SqlTypeDescriptor.varchar(9), command.columnTypeDescriptor(2));
  }

  @Test
  void rejectsDuplicateMissingAndMismatchedPartsBeforePublication() {
    assertInvalid("CREATE TABLE t (a BIGINT, b BIGINT, UNIQUE (a, a))");
    assertInvalid("CREATE TABLE t (a BIGINT, CONSTRAINT same UNIQUE (a), "
        + "CONSTRAINT same CHECK (1 = 1))");
    assertInvalid("CREATE TABLE t (a BIGINT, UNIQUE (missing))");
    assertInvalid("CREATE TABLE t (a BIGINT, b BIGINT, FOREIGN KEY (a, b) "
        + "REFERENCES parent (id))");
    assertInvalid("CREATE TABLE t (a BIGINT, b BIGINT, FOREIGN KEY (a, b) "
        + "REFERENCES parent (id, id))");
    assertEquals(StatusCode.OK, parser.parse(
        "CREATE TABLE t (a BIGINT, FOREIGN KEY (a) REFERENCES first (id), "
            + "FOREIGN KEY (a) REFERENCES second (id))", command));
  }

  @Test
  void rejectsThirtyThirdKeyPartAndRecoversForReuse() {
    StringBuilder sql = new StringBuilder("CREATE TABLE wide (");
    for (int index = 0; index < 33; index++) {
      if (index > 0) sql.append(", ");
      sql.append('c').append(index).append(" BIGINT");
    }
    sql.append(", UNIQUE (");
    for (int index = 0; index < 33; index++) {
      if (index > 0) sql.append(", ");
      sql.append('c').append(index);
    }
    sql.append("))");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(sql, command));
    assertEquals(StatusCode.OK, parser.parse("CREATE TABLE recovered (value BIGINT)", command));
    assertEquals(0, command.tableConstraintCount());
  }

  private void assertConstraint(int index, int kind, String name, String... parts) {
    assertEquals(kind, command.tableConstraintKind(index));
    assertEquals(name, command.tableConstraintName(index).toString());
    assertEquals(parts.length, command.tableConstraintPartCount(index));
    for (int part = 0; part < parts.length; part++) {
      assertEquals(parts[part], command.tableConstraintPartName(index, part).toString());
    }
  }

  private void assertInvalid(String sql) {
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse(sql, command));
  }
}
