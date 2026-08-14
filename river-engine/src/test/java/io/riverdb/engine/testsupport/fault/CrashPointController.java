package io.riverdb.engine.testsupport.fault;

import io.riverdb.base.error.StatusCode;

/**
 * Fixed-capacity ordered fault script. All matching rules observe every event. If several rules
 * fire on the same event, the earliest-added firing rule wins. This explicit insertion-order
 * precedence and {@link #reset()} make exact replay deterministic. Methods serialize access so a
 * controller can safely be shared by the scheduler and file model.
 */
public final class CrashPointController implements FaultInjector {
  private final FaultPoint[] points;
  private final FaultOperation[] operations;
  private final FaultBoundary[] boundaries;
  private final long[] firstOccurrences;
  private final long[] repeatCounts;
  private final FaultAction[] actions;
  private final long[] arguments;
  private final long[] observations;
  private int size;

  public CrashPointController(int capacity) {
    points = new FaultPoint[capacity];
    operations = new FaultOperation[capacity];
    boundaries = new FaultBoundary[capacity];
    firstOccurrences = new long[capacity];
    repeatCounts = new long[capacity];
    actions = new FaultAction[capacity];
    arguments = new long[capacity];
    observations = new long[capacity];
  }

  public synchronized StatusCode addRule(
      FaultPoint point,
      FaultOperation operation,
      long firstOccurrence,
      long repeatCount,
      FaultAction action,
      long argument) {
    return addRule(
        point,
        operation,
        FaultBoundary.BEFORE,
        firstOccurrence,
        repeatCount,
        action,
        argument);
  }

  public synchronized StatusCode addRule(
      FaultPoint point,
      FaultOperation operation,
      FaultBoundary boundary,
      long firstOccurrence,
      long repeatCount,
      FaultAction action,
      long argument) {
    if (point == null
        || operation == null
        || boundary == null
        || action == null
        || firstOccurrence < 1
        || repeatCount < 1
        || argument < 0
        || !action.isCompatibleWith(operation, boundary)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (size == points.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    points[size] = point;
    operations[size] = operation;
    boundaries[size] = boundary;
    firstOccurrences[size] = firstOccurrence;
    repeatCounts[size] = repeatCount;
    actions[size] = action;
    arguments[size] = argument;
    size++;
    return StatusCode.OK;
  }

  @Override
  public synchronized void evaluate(
      FaultPoint point,
      FaultOperation operation,
      long attempt,
      long position,
      int requestedBytes,
      FaultDecision result) {
    evaluate(
        point,
        operation,
        FaultBoundary.BEFORE,
        attempt,
        position,
        requestedBytes,
        result);
  }

  @Override
  public synchronized void evaluate(
      FaultPoint point,
      FaultOperation operation,
      FaultBoundary boundary,
      long attempt,
      long position,
      int requestedBytes,
      FaultDecision result) {
    result.reset();
    boolean selected = false;
    for (int index = 0; index < size; index++) {
      if (points[index] != point
          || operations[index] != operation
          || boundaries[index] != boundary) {
        continue;
      }
      long observation = ++observations[index];
      long first = firstOccurrences[index];
      if (!selected && observation >= first && observation - first < repeatCounts[index]) {
        result.set(actions[index], arguments[index]);
        selected = true;
      }
    }
  }

  public synchronized void reset() {
    for (int index = 0; index < size; index++) {
      observations[index] = 0;
    }
  }

  public synchronized int size() {
    return size;
  }

}
