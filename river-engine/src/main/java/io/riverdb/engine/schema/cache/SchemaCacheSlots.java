package io.riverdb.engine.schema.cache;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;

/** Bounded slot/accounting state used by the synchronized cache facade. */
final class SchemaCacheSlots {
  private final SchemaCacheEntry[] entries;
  private final SchemaCacheSlotScan scan;
  private final int maximumSlots;
  private final long maximumBytes;
  private int usedSlots;
  private long usedBytes;
  private int reservedSlots;
  private long reservedBytes;
  SchemaCacheSlots(int slots, long bytes) {
    entries = new SchemaCacheEntry[slots];
    for (int index = 0; index < slots; index++) entries[index] = new SchemaCacheEntry();
    scan = new SchemaCacheSlotScan(entries);
    maximumSlots = slots;
    maximumBytes = bytes;
  }

  int maximumSlots() { return maximumSlots; }

  long maximumBytes() { return maximumBytes; }

  int size() { return usedSlots; }

  long usedBytes() { return usedBytes; }

  long reservedBytes() { return reservedBytes; }

  int reservedSlots() { return reservedSlots; }

  StatusCode lookupRetained(
      long tableId, long rowLayoutId, SchemaPin pin, SchemaCache owner) {
    SchemaCacheEntry found = scan.findRetained(tableId, rowLayoutId);
    return pin(found, pin, owner);
  }

  StatusCode lookupCurrent(
      long tableId, long schemaId, long rowLayoutId, long generation,
      SchemaPin pin, SchemaCache owner) {
    SchemaCacheEntry found = scan.findExact(tableId, schemaId, rowLayoutId, generation);
    return pin(found, pin, owner);
  }

  private StatusCode pin(
      SchemaCacheEntry found, SchemaPin pin, SchemaCache owner) {
    if (found == null) return StatusCode.CONFLICT;
    found.pinCount++;
    found.sequence = scan.nextSequence();
    pin.attach(owner, found);
    return StatusCode.OK;
  }

  StatusCode reserveSuccessor(
      TableDescriptor descriptor, SchemaAdmission admission, SchemaCache owner) {
    if (descriptor.byteCharge() > maximumBytes) return StatusCode.RESOURCE_EXHAUSTED;
    if (scan.conflictsSuccessor(descriptor)) return StatusCode.CONFLICT;
    return reserveAvailable(descriptor, admission, owner);
  }

  StatusCode reserveLoadedCurrent(
      TableDescriptor descriptor, SchemaAdmission admission, SchemaCache owner) {
    if (descriptor.byteCharge() > maximumBytes) return StatusCode.RESOURCE_EXHAUSTED;
    if (scan.hasExact(descriptor) || scan.hasPending(descriptor.tableId())) {
      return StatusCode.CONFLICT;
    }
    return reserveAvailable(descriptor, admission, owner);
  }

  StatusCode reserveRetained(
      TableDescriptor descriptor, SchemaAdmission admission, SchemaCache owner) {
    if (descriptor.byteCharge() > maximumBytes) return StatusCode.RESOURCE_EXHAUSTED;
    if (scan.hasExact(descriptor)) return StatusCode.CONFLICT;
    return reserveAvailable(descriptor, admission, owner);
  }

  private StatusCode reserveAvailable(
      TableDescriptor descriptor, SchemaAdmission admission, SchemaCache owner) {
    while (!hasCapacity(descriptor.byteCharge())) {
      SchemaCacheEntry victim = scan.oldestUnpinned();
      if (victim == null) return StatusCode.RESOURCE_EXHAUSTED;
      usedSlots--;
      usedBytes -= scan.charge(victim);
      scan.clear(victim);
    }
    SchemaCacheEntry target = scan.freeEntry();
    target.reserved = true;
    target.tableId = descriptor.tableId();
    target.schemaId = descriptor.schemaId();
    target.rowLayoutId = descriptor.rowLayoutId();
    target.catalogGeneration = descriptor.catalogGeneration();
    target.reservedCharge = descriptor.byteCharge();
    target.descriptor = descriptor;
    reservedSlots++;
    reservedBytes += descriptor.byteCharge();
    admission.reserve(owner, target, descriptor);
    return StatusCode.OK;
  }

  StatusCode publish(SchemaAdmission admission, TableDescriptor descriptor, SchemaPin pin,
      SchemaCache owner) {
    SchemaCacheEntry target = admission.entry();
    if (target == null || !target.reserved || target.descriptor != descriptor) {
      return StatusCode.INVARIANT_BROKEN;
    }
    target.reserved = false;
    target.occupied = true;
    target.reservedCharge = 0;
    if (pin != null) target.pinCount++;
    target.sequence = scan.nextSequence();
    usedSlots++;
    usedBytes += descriptor.byteCharge();
    reservedSlots--;
    reservedBytes -= descriptor.byteCharge();
    admission.consume();
    if (pin != null) pin.attach(owner, target);
    return StatusCode.OK;
  }

  StatusCode borrow(
      SchemaAdmission admission, SchemaPin pin, SchemaCache owner) {
    SchemaCacheEntry target = admission.entry();
    if (target == null || !target.reserved || target.descriptor == null) {
      return StatusCode.INVARIANT_BROKEN;
    }
    target.pinCount++;
    pin.attach(owner, target);
    return StatusCode.OK;
  }

  StatusCode cancel(SchemaAdmission admission) {
    SchemaCacheEntry target = admission.entry();
    if (target == null || !target.reserved) return StatusCode.INVARIANT_BROKEN;
    if (target.pinCount != 0) return StatusCode.CONFLICT;
    reservedSlots--;
    reservedBytes -= target.reservedCharge;
    scan.clear(target);
    admission.consume();
    return StatusCode.OK;
  }

  StatusCode release(SchemaPin pin, SchemaCacheEntry entry) {
    if ((!entry.occupied && !entry.reserved) || entry.pinCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    entry.pinCount--;
    pin.clear();
    return StatusCode.OK;
  }

  StatusCode transfer(SchemaPin source, SchemaPin destination, SchemaCacheEntry entry) {
    if ((!entry.occupied && !entry.reserved)
        || entry.pinCount <= 0 || destination.isActive()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.attach(source.owner(), entry);
    source.clear();
    return StatusCode.OK;
  }

  private boolean hasCapacity(long charge) {
    if (usedSlots > maximumSlots - reservedSlots - 1) return false;
    if (reservedBytes > maximumBytes - usedBytes) return false;
    return charge <= maximumBytes - usedBytes - reservedBytes;
  }
}
