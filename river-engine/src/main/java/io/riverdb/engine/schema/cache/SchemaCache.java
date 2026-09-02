package io.riverdb.engine.schema.cache;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;

/** Bounded immutable schema-generation cache with atomic lookup-and-pin. */
public final class SchemaCache {
  private static final int MAXIMUM_BUDGETED_SLOTS = 4_096;
  // Worst-case 72-byte entry object plus its eight-byte owning-array reference.
  private static final long SLOT_CHARGE_BYTES = 80;
  // Cache, slots, scanner, and array headers, rounded conservatively to eight bytes.
  private static final long FIXED_CHARGE_BYTES = 192;
  private static final long TARGET_BYTES_PER_SLOT = 1_024;

  private final SchemaCacheSlots slots;
  private final boolean validConfiguration;
  private final long budgetBytes;
  private final long metadataBytes;

  SchemaCache(int maximumSlots, long maximumBytes) {
    this(maximumSlots, maximumBytes, maximumBytes);
  }

  private SchemaCache(int maximumSlots, long maximumBytes, long totalBudgetBytes) {
    validConfiguration = maximumSlots > 0 && maximumBytes > 0;
    slots = validConfiguration ? new SchemaCacheSlots(maximumSlots, maximumBytes)
        : new SchemaCacheSlots(0, 0);
    budgetBytes = validConfiguration ? totalBudgetBytes : 0;
    metadataBytes = validConfiguration ? totalBudgetBytes - maximumBytes : 0;
  }

  public static StatusCode create(int maximumSlots, long maximumBytes, Result result,
      StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    if (result == null || maximumSlots < 1 || maximumBytes < 1) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid schema cache limits");
    }
    try {
      result.set(new SchemaCache(maximumSlots, maximumBytes));
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "schema cache capacity unavailable");
    }
    if (detail != null) detail.set(StatusCode.OK);
    return StatusCode.OK;
  }

  /** Creates cache slots and descriptor capacity within one admitted total heap budget. */
  public static StatusCode createBudgeted(
      long totalBudgetBytes, Result result, StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    if (result == null || totalBudgetBytes <= FIXED_CHARGE_BYTES + SLOT_CHARGE_BYTES) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid schema cache budget");
    }
    int slotCount = (int) Math.min(
        MAXIMUM_BUDGETED_SLOTS, Math.max(1, totalBudgetBytes / TARGET_BYTES_PER_SLOT));
    long slotBytes = SLOT_CHARGE_BYTES * slotCount;
    long descriptorBytes = totalBudgetBytes - FIXED_CHARGE_BYTES - slotBytes;
    try {
      result.set(new SchemaCache(slotCount, descriptorBytes, totalBudgetBytes));
    } catch (OutOfMemoryError error) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "schema cache capacity unavailable");
    }
    if (detail != null) detail.set(StatusCode.OK);
    return StatusCode.OK;
  }

  public static final class Result {
    private SchemaCache value;

    public void reset() { value = null; }

    public SchemaCache value() { return value; }

    private void set(SchemaCache cache) { value = cache; }
  }

  public int maximumSlots() { return slots.maximumSlots(); }

  public long maximumBytes() { return slots.maximumBytes(); }

  public long budgetBytes() { return budgetBytes; }

  public long metadataBytes() { return metadataBytes; }

  public synchronized int size() { return slots.size(); }

  public synchronized long usedBytes() { return slots.usedBytes(); }

  public synchronized long reservedBytes() { return slots.reservedBytes(); }

  public synchronized int reservedSlots() { return slots.reservedSlots(); }

  /** Whether an active pin was issued by this cache. */
  public synchronized boolean owns(SchemaPin pin) {
    return pin != null && pin.owner() == this && pin.entry() != null;
  }

  public synchronized StatusCode lookup(long tableId, long rowLayoutId, SchemaPin pin) {
    return lookupRetained(tableId, rowLayoutId, pin);
  }

  /** Looks up the newest retained descriptor for historical rows of one exact layout. */
  public synchronized StatusCode lookupRetained(
      long tableId, long rowLayoutId, SchemaPin pin) {
    if (!validConfiguration || tableId <= 0 || rowLayoutId <= 0 || pin == null
        || pin.isActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    return slots.lookupRetained(tableId, rowLayoutId, pin, this);
  }

  /** Looks up only the exact descriptor named by the current durable catalog head. */
  public synchronized StatusCode lookupCurrent(
      long tableId, long rowLayoutId, long catalogGeneration, SchemaPin pin) {
    return lookupCurrent(tableId, 0, rowLayoutId, catalogGeneration, pin);
  }

  /** Looks up the exact durable schema identity named by a catalog head. */
  public synchronized StatusCode lookupCurrent(
      long tableId, long schemaId, long rowLayoutId,
      long catalogGeneration, SchemaPin pin) {
    if (!validConfiguration || tableId <= 0 || rowLayoutId <= 0
        || schemaId < 0 || catalogGeneration <= 0 || pin == null || pin.isActive()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return slots.lookupCurrent(
        tableId, schemaId, rowLayoutId, catalogGeneration, pin, this);
  }

  public synchronized StatusCode lookup(long tableId, long rowLayoutId, SchemaPin pin,
      StatusDetail detail) {
    if (detail != null) detail.reset();
    StatusCode status = lookup(tableId, rowLayoutId, pin);
    if (detail != null && !status.isOk()) detail.set(status);
    return status;
  }

  /** Reserves an unpublished successor after checking the authoritative durable head. */
  public synchronized StatusCode reserveSuccessor(
      TableDescriptor descriptor,
      long currentCatalogGeneration,
      SchemaAdmission admission) {
    if (!validConfiguration || descriptor == null || admission == null || admission.isActive()
        || descriptor.tableId() <= 0 || descriptor.rowLayoutId() <= 0
        || descriptor.catalogGeneration() <= 0 || descriptor.byteCharge() <= 0
        || currentCatalogGeneration < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (descriptor.catalogGeneration() <= currentCatalogGeneration) return StatusCode.CONFLICT;
    return slots.reserveSuccessor(descriptor, admission, this);
  }

  /** Reserves the descriptor named by an already-published authoritative head. */
  public synchronized StatusCode reserveCurrent(
      TableDescriptor descriptor,
      long currentCatalogGeneration,
      SchemaAdmission admission) {
    if (!validConfiguration || descriptor == null || admission == null || admission.isActive()
        || descriptor.tableId() <= 0 || descriptor.rowLayoutId() <= 0
        || descriptor.catalogGeneration() <= 0 || descriptor.byteCharge() <= 0
        || currentCatalogGeneration <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (descriptor.catalogGeneration() != currentCatalogGeneration) return StatusCode.CONFLICT;
    return slots.reserveLoadedCurrent(descriptor, admission, this);
  }

  /** Reserves an exact retained generation loaded for historical-row decoding. */
  public synchronized StatusCode reserveRetained(
      TableDescriptor descriptor, SchemaAdmission admission) {
    if (!validConfiguration || descriptor == null || admission == null || admission.isActive()
        || descriptor.tableId() <= 0 || descriptor.rowLayoutId() <= 0
        || descriptor.catalogGeneration() <= 0 || descriptor.byteCharge() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return slots.reserveRetained(descriptor, admission, this);
  }

  public synchronized StatusCode publish(SchemaAdmission admission, TableDescriptor descriptor,
      SchemaPin pin) {
    if (admission == null || descriptor == null || !admission.belongsTo(this)
        || !admission.matches(descriptor) || (pin != null && pin.isActive())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return slots.publish(admission, descriptor, pin, this);
  }

  synchronized StatusCode borrow(SchemaAdmission admission, SchemaPin pin) {
    if (admission == null || pin == null || pin.isActive()
        || !admission.belongsTo(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return slots.borrow(admission, pin, this);
  }

  public synchronized StatusCode publish(SchemaAdmission admission, TableDescriptor descriptor,
      SchemaPin pin, StatusDetail detail) {
    if (detail != null) detail.reset();
    StatusCode status = publish(admission, descriptor, pin);
    if (detail != null && !status.isOk()) detail.set(status);
    return status;
  }

  synchronized StatusCode cancel(SchemaAdmission admission) {
    return admission != null && admission.belongsTo(this)
        ? slots.cancel(admission) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  synchronized StatusCode release(SchemaPin pin, SchemaCacheEntry entry) {
    if (pin == null || entry == null || pin.owner() != this || pin.entry() != entry) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return slots.release(pin, entry);
  }

  synchronized StatusCode transfer(SchemaPin source, SchemaPin destination,
      SchemaCacheEntry entry) {
    if (source == null || destination == null || source.owner() != this
        || source.entry() != entry) return StatusCode.INVALID_EXTERNAL_INPUT;
    return slots.transfer(source, destination, entry);
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }
}
