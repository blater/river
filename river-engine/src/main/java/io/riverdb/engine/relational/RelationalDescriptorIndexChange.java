package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Builds exact-sized immutable catalog-v2 descriptor successors for index DDL. */
public final class RelationalDescriptorIndexChange {
  private static final String PRIMARY_NAME = "PRIMARY";
  private static final String INTERNAL_PREFIX = "_river_";
  private final KeyDescriptor.Result key = new KeyDescriptor.Result();
  private final RelationalDescriptorIndexArrayAllocator allocator;
  private KeyDescriptor droppedKey;

  public RelationalDescriptorIndexChange() {
    this(RelationalDescriptorIndexArrayAllocator.STANDARD);
  }

  RelationalDescriptorIndexChange(RelationalDescriptorIndexArrayAllocator arrayAllocator) {
    allocator = arrayAllocator;
  }

  public StatusCode add(
      TableDescriptor current, CharSequence name, boolean unique,
      int[] columnOrdinals, int offset, int count,
      TableDescriptor.Result result, StatusDetail detail) {
    reset(result, detail);
    droppedKey = null;
    if (current == null || name == null || columnOrdinals == null || result == null
        || offset < 0 || count <= 0 || offset > columnOrdinals.length - count) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (current.findSecondaryKey(name) >= 0) return StatusCode.CONFLICT;
    int[] parts;
    KeyDescriptor[] secondary;
    try {
      parts = allocator.integers(count);
      System.arraycopy(columnOrdinals, offset, parts, 0, count);
      secondary = allocator.keys(current.secondaryKeyCount() + 1);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = KeyDescriptor.createNamedUnbound(
        KeyDescriptor.KIND_SECONDARY, unique, current.columns(), parts, 0,
        name, key, detail);
    if (!status.isOk()) return status;
    copySecondary(current, secondary, -1);
    secondary[secondary.length - 1] = key.value();
    return successor(current, secondary, result, detail, allocator);
  }

  public StatusCode drop(
      TableDescriptor current, CharSequence name,
      TableDescriptor.Result result, StatusDetail detail) {
    reset(result, detail);
    droppedKey = null;
    if (current == null || name == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (primaryNamed(current.primaryKey(), name)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int removed = current.findSecondaryKey(name);
    if (removed < 0) return StatusCode.CONFLICT;
    KeyDescriptor candidate = current.secondaryKeyAt(removed);
    if (candidate.kind() != KeyDescriptor.KIND_SECONDARY
        || internal(candidate.name())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    KeyDescriptor[] secondary;
    try {
      secondary = allocator.keys(current.secondaryKeyCount() - 1);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    copySecondary(current, secondary, removed);
    StatusCode status = successor(current, secondary, result, detail, allocator);
    if (status.isOk()) droppedKey = candidate;
    return status;
  }

  /** Borrows the key removed by the most recent successful drop proposal. */
  public KeyDescriptor droppedKey() { return droppedKey; }

  private static void copySecondary(
      TableDescriptor current, KeyDescriptor[] destination, int removed) {
    int target = 0;
    for (int index = 0; index < current.secondaryKeyCount(); index++) {
      if (index != removed && target < destination.length) {
        destination[target++] = current.secondaryKeyAt(index);
      }
    }
  }

  private static StatusCode successor(
      TableDescriptor current, KeyDescriptor[] secondary,
      TableDescriptor.Result result, StatusDetail detail,
      RelationalDescriptorIndexArrayAllocator allocator) {
    try {
      KeyDescriptor[] foreign = allocator.keys(current.foreignKeyCount());
      for (int index = 0; index < foreign.length; index++) {
        foreign[index] = current.foreignKeyAt(index);
      }
      return TableDescriptor.createProposedSuccessor(
          current.tableId(), current.rowLayoutId(), current.catalogGeneration(),
          current.columns(), current.primaryKey(), secondary,
          foreign, result, detail);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static void reset(TableDescriptor.Result result, StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
  }

  private static boolean primaryNamed(KeyDescriptor primary, CharSequence name) {
    return primary != null
        && (primary.matchesName(name) || sameIgnoringCase(PRIMARY_NAME, name));
  }

  private static boolean internal(CharSequence name) {
    return name != null && name.length() >= INTERNAL_PREFIX.length()
        && startsWithIgnoringCase(name, INTERNAL_PREFIX);
  }

  private static boolean sameIgnoringCase(CharSequence left, CharSequence right) {
    return left != null && right != null && left.length() == right.length()
        && startsWithIgnoringCase(left, right);
  }

  private static boolean startsWithIgnoringCase(CharSequence value, CharSequence prefix) {
    for (int index = 0; index < prefix.length(); index++) {
      if (Character.toUpperCase(value.charAt(index))
          != Character.toUpperCase(prefix.charAt(index))) return false;
    }
    return true;
  }
}
