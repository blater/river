package io.riverdb.engine.schema.cache;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;

/** Caller-owned reservation held from schema build through durable publication. */
public final class SchemaAdmission {
  private SchemaCache owner;
  private SchemaCacheEntry entry;
  private TableDescriptor descriptor;
  private long byteCharge;
  private boolean active;

  public SchemaAdmission() {
  }

  public boolean isActive() {
    return active;
  }

  public long byteCharge() {
    return active ? byteCharge : 0;
  }

  /** Borrows the admitted descriptor before publication for its creating transaction only. */
  public StatusCode borrow(SchemaPin pin) {
    SchemaCache cache = owner;
    if (!active || cache == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return cache.borrow(this, pin);
  }

  /** Transfers the reserved descriptor into the cache and, optionally, a caller pin. */
  public StatusCode publish(TableDescriptor descriptor, SchemaPin pin) {
    SchemaCache cache = owner;
    if (!active || cache == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return cache.publish(this, descriptor, pin);
  }

  /** Publishes without retaining a pin for the publishing caller. */
  public StatusCode publish(TableDescriptor descriptor) {
    return publish(descriptor, null);
  }

  /** Cancels this reservation exactly once. */
  public StatusCode cancel() {
    SchemaCache cache = owner;
    if (!active || cache == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return cache.cancel(this);
  }

  boolean belongsTo(SchemaCache cache) {
    return active && owner == cache;
  }

  void reserve(SchemaCache cache, SchemaCacheEntry reserved, TableDescriptor descriptor) {
    owner = cache;
    entry = reserved;
    this.descriptor = descriptor;
    byteCharge = descriptor.byteCharge();
    active = true;
  }

  void consume() {
    owner = null;
    entry = null;
    descriptor = null;
    byteCharge = 0;
    active = false;
  }

  SchemaCacheEntry entry() {
    return entry;
  }

  boolean matches(TableDescriptor descriptor) {
    return this.descriptor == descriptor && descriptor.byteCharge() == byteCharge;
  }
}
