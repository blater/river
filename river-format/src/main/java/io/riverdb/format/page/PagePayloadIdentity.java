package io.riverdb.format.page;

import io.riverdb.format.catalog.CatalogKeyspace;

/** Payload metadata validation shared by page encode and decode. */
final class PagePayloadIdentity {
  private PagePayloadIdentity() {
  }

  static boolean isValid(int payloadKind, long ownerKeyId, int payloadBytes) {
    return payloadBytes >= 0
        && payloadBytes <= PageCodec.MAX_PAYLOAD_BYTES
        && (payloadKind == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
            ? ownerKeyId == PageCodec.SCALAR_OWNER_KEY_ID
            : payloadKind == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
                ? CatalogKeyspace.validKeyId(ownerKeyId)
                : payloadKind == PageCodec.PAYLOAD_KIND_FREE
                    && ownerKeyId == PageCodec.SCALAR_OWNER_KEY_ID
                    && payloadBytes == PageCodec.FREE_PAYLOAD_BYTES);
  }
}
