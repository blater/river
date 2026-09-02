package io.riverdb.engine.schema.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class SchemaCacheTest {
  @Test
  void budgetedFactoryChargesEagerSlotsInsideConfiguredBytes() {
    SchemaCache.Result result = new SchemaCache.Result();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, SchemaCache.createBudgeted(8_000_000, result, detail));
    SchemaCache cache = result.value();
    assertEquals(8_000_000, cache.budgetBytes());
    assertEquals(4_096, cache.maximumSlots());
    assertEquals(8_000_000 - 192 - 4_096L * 80, cache.maximumBytes());
    assertEquals(192 + 4_096L * 80, cache.metadataBytes());
    assertEquals(cache.budgetBytes(), cache.metadataBytes() + cache.maximumBytes());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        SchemaCache.createBudgeted(272, result, detail));
    assertNull(result.value());
    assertEquals(StatusCode.OK, SchemaCache.createBudgeted(273, result, detail));
    assertEquals(1, result.value().maximumSlots());
    assertEquals(1, result.value().maximumBytes());
    assertEquals(272, result.value().metadataBytes());
  }

  @Test
  void admissionPublicationLookupAndGenerationSelection() {
    TableDescriptor old = descriptor(10, 4, 1);
    TableDescriptor current = descriptor(10, 4, 2);
    SchemaCache cache = new SchemaCache(3, old.byteCharge() * 3);
    SchemaPin oldPin = new SchemaPin();
    SchemaAdmission admission = new SchemaAdmission();

    assertEquals(StatusCode.OK, cache.reserveSuccessor(old, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(old, oldPin));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(current, 1, admission));
    SchemaPin currentPin = new SchemaPin();
    assertEquals(StatusCode.OK, admission.publish(current, currentPin));
    assertSame(old, oldPin.descriptor());

    SchemaPin lookup = new SchemaPin();
    assertEquals(StatusCode.OK, cache.lookup(10, 4, lookup));
    assertSame(current, lookup.descriptor());
    assertEquals(StatusCode.OK, lookup.release());
    assertEquals(StatusCode.OK, currentPin.release());
    assertEquals(StatusCode.OK, oldPin.release());
  }

  @Test
  void ownershipIsBoundToIssuingCacheAndActiveLifetime() {
    TableDescriptor descriptor = descriptor(12, 6, 1);
    SchemaCache owner = new SchemaCache(1, descriptor.byteCharge());
    SchemaCache foreign = new SchemaCache(1, descriptor.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK, owner.reserveSuccessor(descriptor, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(descriptor, pin));
    assertTrue(owner.owns(pin));
    assertFalse(foreign.owns(pin));
    assertEquals(StatusCode.OK, pin.release());
    assertFalse(owner.owns(pin));
  }

  @Test
  void duplicateAndStaleIdentityFailsBeforeReservation() {
    TableDescriptor current = descriptor(11, 5, 4);
    TableDescriptor stale = descriptor(11, 5, 3);
    SchemaCache cache = new SchemaCache(2, current.byteCharge() * 2);
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(current, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(current));

    assertEquals(StatusCode.CONFLICT, cache.reserveCurrent(current, 4, admission));
    assertEquals(StatusCode.CONFLICT, cache.reserveCurrent(stale, 4, admission));
    assertEquals(0, cache.reservedSlots());
    assertEquals(StatusCode.CONFLICT, cache.lookup(11, 99, new SchemaPin()));
  }

  @Test
  void catalogGenerationIsMonotonicAcrossPhysicalLayouts() {
    TableDescriptor current = descriptor(21, 30, 4);
    TableDescriptor staleNewLayout = descriptor(21, 31, 4);
    TableDescriptor nextLayout = descriptor(21, 31, 5);
    SchemaCache cache = new SchemaCache(2, current.byteCharge() * 2);
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(current, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(current));

    assertEquals(StatusCode.CONFLICT, cache.reserveSuccessor(staleNewLayout, 4, admission));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(nextLayout, 4, admission));
    assertEquals(StatusCode.OK, admission.cancel());
  }

  @Test
  void onlyOneCurrentGenerationMayBeUnderAdmissionPerTable() {
    TableDescriptor next = descriptor(26, 50, 2);
    TableDescriptor later = descriptor(26, 51, 3);
    SchemaCache cache = new SchemaCache(2, next.byteCharge() * 2);
    SchemaAdmission first = new SchemaAdmission();
    SchemaAdmission second = new SchemaAdmission();

    assertEquals(StatusCode.OK, cache.reserveSuccessor(next, 0, first));
    assertEquals(StatusCode.CONFLICT, cache.reserveSuccessor(later, 2, second));
    assertEquals(StatusCode.OK, first.publish(next));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(later, 2, second));
    assertEquals(StatusCode.OK, second.cancel());
  }

  @Test
  void retainedHistoricalLayoutLoadsAfterCurrentGeneration() {
    TableDescriptor current = descriptor(22, 40, 10);
    TableDescriptor historical = descriptor(22, 39, 5);
    SchemaCache cache = new SchemaCache(2, current.byteCharge() * 2);
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(current, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(current));
    assertEquals(StatusCode.CONFLICT, cache.reserveCurrent(historical, 10, admission));
    assertEquals(StatusCode.OK, cache.reserveRetained(historical, admission));
    assertEquals(StatusCode.OK, admission.publish(historical));

    SchemaPin currentPin = new SchemaPin();
    SchemaPin historicalPin = new SchemaPin();
    assertEquals(StatusCode.OK, cache.lookup(22, 40, currentPin));
    assertEquals(StatusCode.OK, cache.lookup(22, 39, historicalPin));
    assertSame(current, currentPin.descriptor());
    assertSame(historical, historicalPin.descriptor());
    assertEquals(StatusCode.OK, currentPin.release());
    assertEquals(StatusCode.OK, historicalPin.release());
  }

  @Test
  void catalogHeadPreventsStaleCurrentAdmissionAfterEviction() {
    TableDescriptor current = descriptor(27, 60, 10);
    TableDescriptor other = descriptor(28, 61, 1);
    TableDescriptor stale = descriptor(27, 59, 5);
    SchemaCache cache = new SchemaCache(1, current.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(current, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(current));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(other, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(other));
    assertEquals(StatusCode.CONFLICT, cache.lookup(27, 60, new SchemaPin()));

    assertEquals(StatusCode.CONFLICT, cache.reserveSuccessor(stale, 10, admission));
    assertEquals(StatusCode.CONFLICT, cache.reserveCurrent(stale, 10, admission));
    assertEquals(0, cache.reservedSlots());
    assertEquals(StatusCode.OK, cache.reserveRetained(stale, admission));
    assertEquals(StatusCode.OK, admission.cancel());
  }

  @Test
  void admissionTransfersOnlyTheExactFrozenDescriptor() {
    TableDescriptor reserved = descriptor(23, 41, 1, "flag");
    TableDescriptor substitute = descriptor(23, 41, 1, "mark");
    assertEquals(reserved.byteCharge(), substitute.byteCharge());
    SchemaCache cache = new SchemaCache(1, reserved.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(reserved, 0, admission));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, admission.publish(substitute));
    assertTrue(admission.isActive());
    assertEquals(StatusCode.OK, admission.publish(reserved));
  }

  @Test
  void cancellationLeavesNoEntryAndReleasesPressure() {
    TableDescriptor first = descriptor(12, 6, 1);
    TableDescriptor second = descriptor(13, 7, 1);
    SchemaCache cache = new SchemaCache(1, first.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();

    assertEquals(StatusCode.OK, cache.reserveSuccessor(first, 0, admission));
    assertEquals(StatusCode.OK, admission.cancel());
    assertFalse(admission.isActive());
    assertEquals(StatusCode.CONFLICT, cache.lookup(12, 6, new SchemaPin()));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(second, 0, admission));
    assertEquals(StatusCode.OK, admission.cancel());
    assertEquals(0, cache.usedBytes());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, admission.cancel());
  }

  @Test
  void creatingTransactionMayBorrowReservedDescriptorUntilPublication() {
    TableDescriptor value = descriptor(18, 12, 1);
    SchemaCache cache = new SchemaCache(1, value.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();
    SchemaPin pin = new SchemaPin();

    assertEquals(StatusCode.OK, cache.reserveSuccessor(value, 0, admission));
    assertEquals(StatusCode.OK, admission.borrow(pin));
    assertSame(value, pin.descriptor());
    assertEquals(StatusCode.CONFLICT, cache.lookupCurrent(18, 12, 1, new SchemaPin()));
    assertEquals(StatusCode.CONFLICT, admission.cancel());
    assertEquals(StatusCode.OK, pin.release());
    assertEquals(StatusCode.OK, admission.publish(value));

    assertEquals(StatusCode.OK, cache.lookupCurrent(18, 12, 1, pin));
    assertSame(value, pin.descriptor());
    assertEquals(StatusCode.OK, pin.release());
  }

  @Test
  void failedTransferCanBeCancelledWithoutPublishingSharedEntry() {
    TableDescriptor reserved = descriptor(19, 12, 1);
    TableDescriptor wrong = descriptor(20, 13, 1);
    SchemaCache cache = new SchemaCache(1, reserved.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(reserved, 0, admission));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, admission.publish(wrong));
    assertTrue(admission.isActive());
    assertEquals(StatusCode.OK, admission.cancel());
    assertEquals(StatusCode.CONFLICT, cache.lookup(19, 12, new SchemaPin()));
    assertEquals(StatusCode.CONFLICT, cache.lookup(20, 13, new SchemaPin()));
  }

  @Test
  void pinnedOldGenerationSurvivesEvictionAndPressure() {
    TableDescriptor old = descriptor(14, 8, 1);
    TableDescriptor evictable = descriptor(15, 9, 1);
    TableDescriptor replacement = descriptor(16, 10, 1);
    SchemaCache cache = new SchemaCache(2, old.byteCharge() * 2);
    SchemaAdmission admission = new SchemaAdmission();
    SchemaPin oldPin = new SchemaPin();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(old, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(old, oldPin));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(evictable, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(evictable));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(replacement, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(replacement));

    SchemaPin stillThere = new SchemaPin();
    assertEquals(StatusCode.OK, cache.lookup(14, 8, stillThere));
    assertSame(old, stillThere.descriptor());
    assertEquals(StatusCode.CONFLICT, cache.lookup(15, 9, new SchemaPin()));
    assertEquals(StatusCode.OK, stillThere.release());
    assertEquals(StatusCode.OK, oldPin.release());
  }

  @Test
  void stalePinnedGenerationCannotSatisfyCurrentLookupAfterCurrentEviction() {
    TableDescriptor old = descriptor(31, 70, 1);
    TableDescriptor current = descriptor(31, 70, 2);
    TableDescriptor pressure = descriptor(32, 71, 1);
    SchemaCache cache = new SchemaCache(2, old.byteCharge() * 2);
    SchemaAdmission admission = new SchemaAdmission();
    SchemaPin oldPin = new SchemaPin();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(old, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(old, oldPin));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(current, 1, admission));
    assertEquals(StatusCode.OK, admission.publish(current));
    assertEquals(StatusCode.OK, cache.reserveSuccessor(pressure, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(pressure));

    assertEquals(StatusCode.CONFLICT,
        cache.lookupCurrent(31, 70, 2, new SchemaPin()));
    SchemaPin retained = new SchemaPin();
    assertEquals(StatusCode.OK, cache.lookupRetained(31, 70, retained));
    assertSame(old, retained.descriptor());
    assertSame(old, oldPin.descriptor());
    assertEquals(StatusCode.OK, retained.release());
    assertEquals(StatusCode.OK, oldPin.release());
  }

  @Test
  void transferAndReleaseAreExactOnce() {
    TableDescriptor value = descriptor(17, 11, 1);
    SchemaCache cache = new SchemaCache(1, value.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();
    SchemaPin source = new SchemaPin();
    SchemaPin destination = new SchemaPin();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(value, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(value, source));
    assertEquals(StatusCode.OK, source.transferTo(destination));
    assertFalse(source.isActive());
    assertTrue(destination.isActive());
    assertNull(source.descriptor());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, source.release());
    assertEquals(StatusCode.OK, destination.release());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, destination.release());
  }

  @Test
  void lookupPinAtomicallyPreventsConcurrentEvictionUntilRelease() throws Exception {
    TableDescriptor first = descriptor(24, 42, 1);
    TableDescriptor replacement = descriptor(25, 43, 1);
    SchemaCache cache = new SchemaCache(1, first.byteCharge());
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK, cache.reserveSuccessor(first, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(first));

    CountDownLatch pinned = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread reader = new Thread(() -> {
      try {
        SchemaPin pin = new SchemaPin();
        assertEquals(StatusCode.OK, cache.lookup(24, 42, pin));
        pinned.countDown();
        release.await();
        assertSame(first, pin.descriptor());
        assertEquals(StatusCode.OK, pin.release());
      } catch (Throwable throwable) {
        failure.set(throwable);
      }
    });
    reader.start();
    pinned.await();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        cache.reserveSuccessor(replacement, 0, admission));
    release.countDown();
    reader.join();
    assertNull(failure.get());
    assertEquals(StatusCode.OK, cache.reserveSuccessor(replacement, 0, admission));
    assertEquals(StatusCode.OK, admission.publish(replacement));
  }

  private static TableDescriptor descriptor(long tableId, long rowLayoutId, long generation) {
    return descriptor(tableId, rowLayoutId, generation, "flag");
  }

  private static TableDescriptor descriptor(
      long tableId, long rowLayoutId, long generation, CharSequence name) {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BOOLEAN}, new CharSequence[] {name},
        new boolean[] {false}, columns));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        tableId, rowLayoutId, generation, columns.value(), null, null, null, result, null));
    return result.value();
  }
}
