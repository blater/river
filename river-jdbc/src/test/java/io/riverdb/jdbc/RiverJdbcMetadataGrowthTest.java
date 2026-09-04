package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class RiverJdbcMetadataGrowthTest {
  @Test
  void tableMetadataGrowsBeyondFormerShortSizedLimit() throws Exception {
    RiverCatalogResultSet tables = RiverCatalogResultSet.tables(
        null, null, "%", true, true);
    tables.tableNameCharacters[0] = 't';
    tables.tableNameLength = 1;

    for (int index = 0; index < 32_769; index++) tables.appendTable((byte) 1);

    assertEquals(32_769, tables.tableCount);
  }

  @Test
  void columnMetadataGrowsBeyondFormerShortSizedLimit() throws Exception {
    RiverColumnsResultSet columns = new RiverColumnsResultSet(
        null, null, "%", "%");

    for (int index = 0; index < 32_769; index++) columns.appendRelation("t");
  }
}
