package io.riverdb.engine.relational;

import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;

/** Exact durable table-generation identity carried by a statistics manifest. */
final class CatalogStatisticsIdentity {
  private CatalogStatisticsIdentity() { }

  static boolean matches(CatalogDefinitionManifest manifest, TableDefinition table) {
    return manifest.kind() == CatalogDefinitionManifestCodec.KIND_STATISTICS
        && manifest.objectId() == table.tableId()
        && manifest.schemaId() == table.statisticsSchemaId()
        && manifest.rowLayoutId() == table.statisticsRowLayoutId()
        && manifest.catalogGeneration() == table.statisticsCatalogGeneration()
        && manifest.columnCount() == table.columnCount()
        && manifest.keyPartCount() == 0
        && manifest.logicalCount() == table.columnCount();
  }
}
