package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Caller-owned fixed-capacity carrier for one synchronous directory scan.
 *
 * <p>Providers borrow stable entry-name references into this carrier; they do not allocate one
 * result object per entry. When {@link #complete()} is false, {@code list} returns
 * {@link StatusCode#RESOURCE_EXHAUSTED}. The caller may retry with a larger bounded carrier.
 */
public final class DirectoryListResult {
  private final String[] names;
  private final DirectoryEntryType[] types;
  private int size;
  private boolean complete;
  private long providerGeneration;

  public DirectoryListResult(int capacity) {
    int boundedCapacity = Math.max(0, capacity);
    names = new String[boundedCapacity];
    types = new DirectoryEntryType[boundedCapacity];
  }

  public int capacity() {
    return names.length;
  }

  public int size() {
    return size;
  }

  public String name(int index) {
    return names[index];
  }

  public DirectoryEntryType type(int index) {
    return types[index];
  }

  public boolean complete() {
    return complete;
  }

  public long providerGeneration() {
    return providerGeneration;
  }

  /** Provider adapter hook; application code should treat the carrier as output-only. */
  public StatusCode add(String name, DirectoryEntryType type) {
    if (size == names.length) {
      complete = false;
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    names[size] = name;
    types[size] = type;
    size++;
    return StatusCode.OK;
  }

  /** Provider adapter hook marking a fully enumerated snapshot. */
  public void finish(long generation) {
    providerGeneration = generation;
    complete = true;
  }

  public void reset() {
    for (int index = 0; index < size; index++) {
      names[index] = null;
      types[index] = null;
    }
    size = 0;
    complete = false;
    providerGeneration = 0;
  }
}
