package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Reusable transaction-scoped name to published descriptor bindings. */
final class RelationalDescriptorBindingStorage {
  // Includes one slot, its fixed name storage, and retained slot-array overhead.
  private static final long ENTRY_CHARGE_BYTES = 512;
  private static final long ARRAY_CHARGE_BYTES = 64;

  private final RelationalDatabaseServices services;
  private final RelationalDescriptorNames names;
  private final SchemaPin lookupPin = new SchemaPin();
  private final SqlRuntimeLeaseResult runtimeResult = new SqlRuntimeLeaseResult();
  private BindingEntry[] entries = new BindingEntry[0];
  private int active;
  private SqlRuntimeLease runtime;

  RelationalDescriptorBindingStorage(
      RelationalDatabaseServices databaseServices,
      RelationalDescriptorNames descriptorNames) {
    services = databaseServices;
    names = descriptorNames;
  }

  StatusCode resolve(CharSequence name, SchemaPin destination, StatusDetail detail) {
    if (entries == null) return StatusCode.CLOSED;
    if (services == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    BindingEntry found = find(name);
    if (found != null) {
      StatusCode status = services.share(found.pin, destination);
      setDetail(detail, status);
      return status;
    }
    StatusCode status = names.open(name, lookupPin, detail);
    if (!status.isOk()) return status;
    BindingEntry target = slot();
    if (target == null) {
      StatusCode capacity = reserveStatus;
      lookupPin.release();
      setDetail(detail, capacity);
      return capacity;
    }
    status = services.share(lookupPin, target.pin);
    if (status.isOk()) status = lookupPin.transferTo(destination);
    if (!status.isOk()) {
      if (target.pin.isActive()) target.pin.release();
      lookupPin.reset();
      setDetail(detail, status);
      return status;
    }
    target.bind(name);
    active++;
    setDetail(detail, StatusCode.OK);
    return StatusCode.OK;
  }

  StatusCode clear() {
    if (entries == null) return StatusCode.CLOSED;
    StatusCode first = StatusCode.OK;
    for (int index = 0; index < entries.length; index++) {
      BindingEntry entry = entries[index];
      if (!entry.active) continue;
      StatusCode status = entry.pin.release();
      if (!status.isOk()) {
        if (first.isOk()) first = status;
        continue;
      }
      entry.clear();
      active--;
    }
    return first;
  }

  StatusCode close() {
    if (entries == null) return StatusCode.OK;
    StatusCode status = clear();
    if (status.isOk() && runtime != null) status = runtime.close();
    if (status.isOk()) {
      runtime = null;
      entries = null;
    }
    return status;
  }

  private BindingEntry find(CharSequence name) {
    if (name == null) return null;
    for (int index = 0; index < entries.length; index++) {
      BindingEntry entry = entries[index];
      if (entry.active && entry.matches(name)) return entry;
    }
    return null;
  }

  private StatusCode reserveStatus;

  private BindingEntry slot() {
    reserveStatus = StatusCode.OK;
    for (int index = 0; index < entries.length; index++) {
      if (!entries[index].active) return entries[index];
    }
    int oldLength = entries.length;
    if (oldLength == Integer.MAX_VALUE) {
      reserveStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    int newLength = oldLength == 0 ? 1 : oldLength > Integer.MAX_VALUE / 2
        ? Integer.MAX_VALUE : oldLength * 2;
    long added = ARRAY_CHARGE_BYTES + (long) (newLength - oldLength) * ENTRY_CHARGE_BYTES;
    if (added <= 0) {
      reserveStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    StatusCode status = ensureRuntime();
    if (status.isOk()) status = runtime.reserve(added);
    if (!status.isOk()) {
      reserveStatus = status;
      return null;
    }
    BindingEntry[] expanded;
    try {
      expanded = new BindingEntry[newLength];
      for (int index = 0; index < newLength; index++) {
        expanded[index] = index < oldLength ? entries[index] : new BindingEntry();
      }
    } catch (OutOfMemoryError failure) {
      runtime.releaseReserved(added);
      reserveStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    entries = expanded;
    return entries[oldLength];
  }

  private StatusCode ensureRuntime() {
    if (runtime != null) return StatusCode.OK;
    if (services == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    runtimeResult.reset();
    StatusCode status = services.acquireRuntime(runtimeResult);
    if (status.isOk()) runtime = runtimeResult.lease();
    return status;
  }

  private static void setDetail(StatusDetail detail, StatusCode status) {
    if (detail != null) {
      detail.reset();
      if (!status.isOk()) detail.set(status);
    }
  }

  private static final class BindingEntry {
    private final SchemaPin pin = new SchemaPin();
    private final char[] name = new char[TableSchema.MAXIMUM_NAME_LENGTH];
    private int nameLength;
    private boolean active;

    void bind(CharSequence value) {
      nameLength = value.length();
      for (int index = 0; index < nameLength; index++) name[index] = value.charAt(index);
      active = true;
    }

    boolean matches(CharSequence value) {
      if (value.length() != nameLength) return false;
      for (int index = 0; index < nameLength; index++) {
        if (value.charAt(index) != name[index]) return false;
      }
      return true;
    }

    void clear() {
      for (int index = 0; index < nameLength; index++) name[index] = 0;
      nameLength = 0;
      active = false;
    }
  }
}
