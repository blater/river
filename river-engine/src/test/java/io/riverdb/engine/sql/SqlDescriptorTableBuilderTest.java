package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import org.junit.jupiter.api.Test;

final class SqlDescriptorTableBuilderTest {
  private final SqlParser parser = new SqlParser();
  private final SqlCommand command = new SqlCommand();
  private final StatusDetail detail = new StatusDetail(128);
  private final SqlDescriptorTableBuilder builder = new SqlDescriptorTableBuilder();

  @Test
  void freezesCompositePrimaryAndUniqueKeysWithoutStaleState() {
    assertEquals(StatusCode.OK, parser.parse(
        "CREATE TABLE membership (tenant BIGINT, member BIGINT, email BIGINT, "
            + "PRIMARY KEY (tenant, member), "
            + "CONSTRAINT uq_email UNIQUE (email, tenant))",
        command));
    assertEquals(StatusCode.OK, builder.freeze(command, detail));
    TableDescriptor table = builder.descriptor();
    assertEquals(2, table.primaryKey().partCount());
    assertEquals(0, table.primaryKey().columnOrdinalAt(0));
    assertEquals(1, table.primaryKey().columnOrdinalAt(1));
    assertEquals(1, table.secondaryKeyCount());
    KeyDescriptor unique = table.secondaryKeyAt(0);
    assertEquals(KeyDescriptor.KIND_UNIQUE, unique.kind());
    assertEquals(2, unique.columnOrdinalAt(0));
    assertEquals(0, unique.columnOrdinalAt(1));
    assertEquals(StatusCode.OK, builder.build(command, detail));

    assertEquals(StatusCode.OK,
        parser.parse("CREATE TABLE keyless (value BIGINT)", command));
    assertEquals(StatusCode.OK, builder.freeze(command, detail));
    assertNull(builder.descriptor().primaryKey());
    assertEquals(0, builder.descriptor().secondaryKeyCount());
    assertEquals(StatusCode.OK, builder.build(command, detail));
  }

  @Test
  void rejectsUnrepresentableChecksAndFreezesForeignKeyBaseShape() {
    assertEquals(StatusCode.OK,
        parser.parse("CREATE TABLE checked (value BIGINT, CHECK (1=1))", command));
    assertEquals(StatusCode.FEATURE_NOT_SUPPORTED, builder.build(command, detail));

    assertEquals(StatusCode.OK, parser.parse(
        "CREATE TABLE child (tenant BIGINT, member BIGINT, "
            + "FOREIGN KEY (tenant, member) REFERENCES parent (tenant, member))",
        command));
    assertEquals(StatusCode.OK, builder.freeze(command, detail));
    assertNotNull(builder.descriptor());
    assertEquals(0, builder.descriptor().foreignKeyCount());
    assertEquals(StatusCode.OK, builder.build(command, detail));
    assertEquals(0, builder.descriptor().foreignKeyCount());
  }
}
