package io.riverdb.engine.schema.cache;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;

/**
 * Caller-owned, reusable reference to one immutable published schema generation.
 * A handle is caller-confined; independent handles may use the cache concurrently.
 */
public final class SchemaPin {
  private SchemaCache owner;
  private SchemaCacheEntry entry;

  public SchemaPin() {
  }

  /** Whether this handle currently owns one cache pin. */
  public boolean isActive() {
    return owner != null;
  }

  /** Whether the borrowed generation has crossed its durable cache publication boundary. */
  public boolean isPublished() {
    return entry != null && entry.occupied;
  }

  /** Borrowed descriptor, valid until {@link #release()} or transfer. */
  public TableDescriptor descriptor() {
    return entry == null ? null : entry.descriptor;
  }

  public long tableId() {
    return entry == null ? 0 : entry.tableId;
  }

  public long schemaId() {
    return entry == null ? 0 : entry.schemaId;
  }

  public long rowLayoutId() {
    return entry == null ? 0 : entry.rowLayoutId;
  }

  public long catalogGeneration() {
    return entry == null ? 0 : entry.catalogGeneration;
  }

  /** Releases this pin exactly once. */
  public StatusCode release() {
    SchemaCache cache = owner;
    if (cache == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return cache.release(this, entry);
  }

  /** Alias for callers that model pin lifetime as close. */
  public StatusCode close() {
    return release();
  }

  /** Clears an inactive handle, or releases its one active pin. */
  public StatusCode reset() {
    return owner == null ? StatusCode.OK : release();
  }

  /** Moves this pin to an inactive destination without changing the cache count. */
  public StatusCode transferTo(SchemaPin destination) {
    SchemaCache cache = owner;
    if (cache == null || destination == null || destination == this) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return cache.transfer(this, destination, entry);
  }

  void attach(SchemaCache cache, SchemaCacheEntry newEntry) {
    owner = cache;
    entry = newEntry;
  }

  void clear() {
    owner = null;
    entry = null;
  }

  SchemaCache owner() {
    return owner;
  }

  SchemaCacheEntry entry() {
    return entry;
  }
}
