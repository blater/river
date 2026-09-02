package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
final class CatalogDescriptorKeyBinding {
  private CatalogDescriptorKeyBinding() { }
  static StatusCode bind(TableDescriptor source, CatalogReservation reservation,
      TableDescriptor.Result result, StatusDetail detail) {
    int count = CatalogTableKeys.count(source);
    if (!CatalogReservedKeyRange.matches(reservation, count)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    KeyDescriptor[] secondary, foreign;
    KeyDescriptor.Result keyResult;
    try {
      secondary = new KeyDescriptor[source.secondaryKeyCount()];
      foreign = new KeyDescriptor[source.foreignKeyCount()];
      keyResult = new KeyDescriptor.Result();
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long next = reservation.firstKeyId();
    KeyDescriptor primary = null;
    StatusCode status = StatusCode.OK;
    if (source.primaryKey() != null) {
      status = CatalogKeyIdentityBinding.bind(
          source.primaryKey(), source.columns(), next++, keyResult, detail);
      primary = keyResult.value();
    }
    for (int index = 0; status.isOk() && index < secondary.length; index++) {
      status = CatalogKeyIdentityBinding.bind(
          source.secondaryKeyAt(index), source.columns(), next++, keyResult, detail);
      secondary[index] = keyResult.value();
    }
    for (int index = 0; status.isOk() && index < foreign.length; index++) {
      long referencedKeyId = referencedKeyId(
          source.foreignKeyAt(index), primary, secondary);
      if (referencedKeyId == 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      status = CatalogKeyIdentityBinding.bind(
          source.foreignKeyAt(index), source.columns(), next++, referencedKeyId,
          keyResult, detail);
      foreign[index] = keyResult.value();
    }
    return status.isOk() ? TableDescriptor.createCatalogBound(
        reservation.objectId(), reservation.schemaId(),
        reservation.rowLayoutId(), reservation.catalogGeneration(), source.columns(), primary,
        secondary, foreign, result, detail) : status;
  }

  private static long referencedKeyId(
      KeyDescriptor foreign, KeyDescriptor primary, KeyDescriptor[] secondary) {
    long referenced = foreign.referencedKeyId();
    if (referenced > 0) return referenced;
    long ordinal = -referenced - 1;
    if (primary != null) {
      if (ordinal == 0) return primary.keyId();
      ordinal--;
    }
    return ordinal >= 0 && ordinal < secondary.length
        ? secondary[(int) ordinal].keyId() : 0;
  }
}
