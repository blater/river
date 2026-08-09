package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultDecision;
import io.riverdb.platform.fault.FaultInjector;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;

/**
 * Fixed-capacity ordered fault script. Rules count matching observations independently, which
 * makes reset and exact replay deterministic.
 */
public final class CrashPointController implements FaultInjector {
  private final FaultPoint[] points;
  private final FaultOperation[] operations;
  private final long[] firstOccurrences;
  private final long[] repeatCounts;
  private final FaultAction[] actions;
  private final long[] arguments;
  private final long[] observations;
  private int size;

  public CrashPointController(int capacity) {
    points = new FaultPoint[capacity];
    operations = new FaultOperation[capacity];
    firstOccurrences = new long[capacity];
    repeatCounts = new long[capacity];
    actions = new FaultAction[capacity];
    arguments = new long[capacity];
    observations = new long[capacity];
  }

  public StatusCode addRule(
      FaultPoint point,
      FaultOperation operation,
      long firstOccurrence,
      long repeatCount,
      FaultAction action,
      long argument) {
    if (firstOccurrence < 1 || repeatCount < 1 || argument < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (size == points.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    points[size] = point;
    operations[size] = operation;
    firstOccurrences[size] = firstOccurrence;
    repeatCounts[size] = repeatCount;
    actions[size] = action;
    arguments[size] = argument;
    size++;
    return StatusCode.OK;
  }

  @Override
  public void evaluate(
      FaultPoint point,
      FaultOperation operation,
      long attempt,
      long position,
      int requestedBytes,
      FaultDecision result) {
    result.reset();
    for (int index = 0; index < size; index++) {
      if (points[index] != point || operations[index] != operation) {
        continue;
      }
      long observation = ++observations[index];
      long first = firstOccurrences[index];
      if (observation >= first && observation - first < repeatCounts[index]) {
        result.set(actions[index], arguments[index]);
        return;
      }
    }
  }

  public void reset() {
    for (int index = 0; index < size; index++) {
      observations[index] = 0;
    }
  }

  public int size() {
    return size;
  }
}
