package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;

/** Rebinds a provisional descriptor to independently reserved durable identities. */
final class CatalogDescriptorIdentity {
  private CatalogDescriptorIdentity() { }

  static StatusCode bind(TableDescriptor provisional, CatalogReservation reservation,
      TableDescriptor.Result result, StatusDetail detail) {
    if (provisional == null || reservation == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return CatalogDescriptorKeyBinding.bind(provisional, reservation, result, detail);
  }
}
