package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Ordered primary/secondary key-part enumeration for SHOW INDEXES. */
final class SqlDescriptorCatalogIndexes {
  private TableDescriptor table;
  private int index;
  private int part;
  private int publishedPart;

  void begin(TableDescriptor descriptor) {
    table = descriptor;
    index = descriptor.primaryKey() == null ? 0 : -1;
    part = 0;
    publishedPart = 0;
  }

  StatusCode next(SqlPhysicalPlan plan, SqlCatalogRowBuffer row) {
    while (index < table.secondaryKeyCount()) {
      boolean primary = index < 0;
      KeyDescriptor key = primary ? table.primaryKey() : table.secondaryKeyAt(index);
      if (key == null || !primary && !key.hasName()) return StatusCode.CORRUPTION;
      if (part >= key.partCount()) {
        index++;
        part = 0;
        continue;
      }
      publishedPart = part++;
      return row.loadIndex(plan, table, key, publishedPart, primary);
    }
    return StatusCode.CONFLICT;
  }

  int publishedPart() { return publishedPart; }
}
