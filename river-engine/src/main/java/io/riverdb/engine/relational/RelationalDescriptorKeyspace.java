package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;

/** Checked physical namespace for catalog-v2 table rows. */
final class RelationalDescriptorKeyspace {
  static final long FIRST_SPACE = CatalogKeyspace.FIRST_RELATIONAL_SPACE;
  static final long NAME_MAP_SPACE = FIRST_SPACE;

  private RelationalDescriptorKeyspace() {
  }

  static StatusCode validate(long objectId) {
    return CatalogKeyspace.validObjectHead(objectId)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  static long baseRows(long objectId) {
    return CatalogKeyspace.relationalBaseRowSpace(objectId);
  }

}
