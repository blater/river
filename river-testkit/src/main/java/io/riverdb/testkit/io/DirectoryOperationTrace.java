package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryDurability;

/** Fixed-capacity operation trace; saturation is observable and never changes I/O outcomes. */
public final class DirectoryOperationTrace {
  private final DirectoryOperation[] operations;
  private final StatusCode[] statuses;
  private final DirectoryDurability[] durabilities;
  private final long[] generations;
  private int size;

  public DirectoryOperationTrace(int capacity) {
    int bounded = Math.max(0, capacity);
    operations = new DirectoryOperation[bounded];
    statuses = new StatusCode[bounded];
    durabilities = new DirectoryDurability[bounded];
    generations = new long[bounded];
  }

  StatusCode append(
      DirectoryOperation operation,
      StatusCode status,
      DirectoryDurability durability,
      long generation) {
    if (size == operations.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    operations[size] = operation;
    statuses[size] = status;
    durabilities[size] = durability;
    generations[size] = generation;
    size++;
    return StatusCode.OK;
  }

  public int size() {
    return size;
  }

  public int capacity() {
    return operations.length;
  }

  public DirectoryOperation operation(int index) {
    return operations[index];
  }

  public StatusCode status(int index) {
    return statuses[index];
  }

  public DirectoryDurability durability(int index) {
    return durabilities[index];
  }

  public long generation(int index) {
    return generations[index];
  }
}
