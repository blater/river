package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;

/** Fixed-capacity trace of injected actions, including the triggering I/O/scheduler sequence. */
public final class FaultTrace {
  private final FaultPoint[] points;
  private final FaultOperation[] operations;
  private final FaultAction[] actions;
  private final long[] arguments;
  private final long[] sequences;
  private int size;

  public FaultTrace(int capacity) {
    int boundedCapacity = Math.max(0, capacity);
    points = new FaultPoint[boundedCapacity];
    operations = new FaultOperation[boundedCapacity];
    actions = new FaultAction[boundedCapacity];
    arguments = new long[boundedCapacity];
    sequences = new long[boundedCapacity];
  }

  synchronized StatusCode append(
      FaultPoint point,
      FaultOperation operation,
      FaultAction action,
      long argument,
      long sequence) {
    if (size == points.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    points[size] = point;
    operations[size] = operation;
    actions[size] = action;
    arguments[size] = argument;
    sequences[size] = sequence;
    size++;
    return StatusCode.OK;
  }

  public synchronized int size() {
    return size;
  }

  public int capacity() {
    return points.length;
  }

  public synchronized FaultPoint point(int index) {
    return points[index];
  }

  public synchronized FaultOperation operation(int index) {
    return operations[index];
  }

  public synchronized FaultAction action(int index) {
    return actions[index];
  }

  public synchronized long argument(int index) {
    return arguments[index];
  }

  public synchronized long sequence(int index) {
    return sequences[index];
  }

  public synchronized void reset() {
    size = 0;
  }
}
