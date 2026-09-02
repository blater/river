package io.riverdb.engine.table;

import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;

/** Validates ownership metadata before a new page enters operation state. */
final class IndexedPageIdentity {
  private IndexedPageIdentity() { }

  static boolean valid(int payloadKind, long ownerKeyId) {
    return payloadKind == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
        ? ownerKeyId == PageCodec.SCALAR_OWNER_KEY_ID
        : payloadKind == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
            ? CatalogKeyspace.validKeyId(ownerKeyId)
            : payloadKind == PageCodec.PAYLOAD_KIND_FREE
                && ownerKeyId == PageCodec.SCALAR_OWNER_KEY_ID;
  }
}
