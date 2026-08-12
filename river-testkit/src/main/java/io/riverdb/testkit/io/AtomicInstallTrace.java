package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.AtomicInstallPhase;
import io.riverdb.platform.file.AtomicInstallStep;
import io.riverdb.platform.file.DirectoryDurability;

/** Fixed-capacity trace of install progress; saturation never grows the trace. */
public final class AtomicInstallTrace {
  private final AtomicInstallStep[] steps;
  private final AtomicInstallPhase[] phasesBefore;
  private final AtomicInstallPhase[] phasesAfter;
  private final DirectoryDurability[] durabilities;
  private final StatusCode[] statuses;
  private final boolean[] completionsPending;
  private int size;

  public AtomicInstallTrace(int capacity) {
    int boundedCapacity = Math.max(0, capacity);
    steps = new AtomicInstallStep[boundedCapacity];
    phasesBefore = new AtomicInstallPhase[boundedCapacity];
    phasesAfter = new AtomicInstallPhase[boundedCapacity];
    durabilities = new DirectoryDurability[boundedCapacity];
    statuses = new StatusCode[boundedCapacity];
    completionsPending = new boolean[boundedCapacity];
  }

  StatusCode append(
      AtomicInstallStep step,
      AtomicInstallPhase phaseBefore,
      AtomicInstallPhase phaseAfter,
      DirectoryDurability durability,
      StatusCode status,
      boolean completionPending) {
    if (size == steps.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    steps[size] = step;
    phasesBefore[size] = phaseBefore;
    phasesAfter[size] = phaseAfter;
    durabilities[size] = durability;
    statuses[size] = status;
    completionsPending[size] = completionPending;
    size++;
    return StatusCode.OK;
  }

  public int size() {
    return size;
  }

  public int capacity() {
    return steps.length;
  }

  public AtomicInstallStep step(int index) {
    return steps[index];
  }

  public AtomicInstallPhase phaseBefore(int index) {
    return phasesBefore[index];
  }

  public AtomicInstallPhase phaseAfter(int index) {
    return phasesAfter[index];
  }

  public DirectoryDurability durability(int index) {
    return durabilities[index];
  }

  public StatusCode status(int index) {
    return statuses[index];
  }

  public boolean completionPending(int index) {
    return completionsPending[index];
  }

  public void reset() {
    size = 0;
  }
}
