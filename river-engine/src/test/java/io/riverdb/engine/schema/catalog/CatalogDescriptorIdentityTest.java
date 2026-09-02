package io.riverdb.engine.schema.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.catalog.CatalogKeyspace;
import org.junit.jupiter.api.Test;

final class CatalogDescriptorIdentityTest {
  @Test
  void bindsExactReservedKeyRangeAndPreservesForeignReference() {
    TableDescriptor source = CatalogDescriptorIdentityTestFixture.table();
    CatalogReservation reservation = new CatalogReservation();
    reservation.setInitial(11, 12, 13, 1, 14, 15, 1, 100, 3);
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK,
        CatalogDescriptorIdentity.bind(source, reservation, result, null));
    TableDescriptor bound = result.value();
    assertEquals(100, bound.primaryKey().keyId());
    assertEquals(101, bound.secondaryKeyAt(0).keyId());
    assertEquals(102, bound.foreignKeyAt(0).keyId());
    assertEquals(999, bound.foreignKeyAt(0).referencedKeyId());

    reservation.setInitial(11, 12, 13, 1, 14, 15, 1, 103, 2);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogDescriptorIdentity.bind(source, reservation, result, null));
  }

  @Test
  void permitsKeylessTableAtKeyIdExhaustionSentinel() {
    CatalogReservation reservation = new CatalogReservation();
    reservation.setInitial(
        11, 12, 13, 1, 14, 15, 1, CatalogKeyspace.KEY_ID_EXHAUSTED, 0);
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, CatalogDescriptorIdentity.bind(
        CatalogDescriptorIdentityTestFixture.tableWithoutKeys(), reservation, result, null));
  }
}
