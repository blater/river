package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.file.AtomicFileInstaller;
import io.riverdb.platform.file.AtomicInstallStep;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.testkit.crash.CrashPointController;

/** Deterministic provider adapter used by the shared suite and as the NIO adapter template. */
public final class FaultingAtomicInstallContractProvider
    implements AtomicInstallContractProvider {
  private final CrashPointController controller;
  private final AtomicInstallFaultPoints points;
  private final AtomicInstallTrace trace;
  private final FaultingAtomicFileStore store;

  public FaultingAtomicInstallContractProvider(int ruleCapacity, int traceCapacity) {
    FaultPointRegistry registry = new FaultPointRegistry(12);
    FaultPointSlot[] slots = new FaultPointSlot[12];
    for (int index = 0; index < slots.length; index++) {
      slots[index] = new FaultPointSlot();
    }
    points = new AtomicInstallFaultPoints(
        point(registry, slots[0], "install.temp-create.before"),
        point(registry, slots[1], "install.temp-create.after"),
        point(registry, slots[2], "install.temp-write.before"),
        point(registry, slots[3], "install.temp-write.after"),
        point(registry, slots[4], "install.temp-force.before"),
        point(registry, slots[5], "install.temp-force.after"),
        point(registry, slots[6], "install.replace.before"),
        point(registry, slots[7], "install.replace.after"),
        point(registry, slots[8], "install.directory-force.before"),
        point(registry, slots[9], "install.directory-force.after"),
        point(registry, slots[10], "install.reopen-verify.before"),
        point(registry, slots[11], "install.reopen-verify.after"));
    controller = new CrashPointController(ruleCapacity);
    trace = new AtomicInstallTrace(traceCapacity);
    store = new FaultingAtomicFileStore(4, 256, 8, controller, points, trace);
  }

  @Override
  public AtomicFileInstaller installer() {
    return store;
  }

  @Override
  public DurableDirectory directory() {
    return store;
  }

  @Override
  public StatusCode script(
      AtomicInstallStep step,
      FaultBoundary boundary,
      FaultAction action,
      long argument) {
    FaultPoint point = point(step, boundary);
    FaultOperation operation = operation(step);
    if (point == null || operation == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return controller.addRule(point, operation, boundary, 1, 1, action, argument);
  }

  @Override
  public StatusCode crash() {
    return store.crash();
  }

  @Override
  public StatusCode restart() {
    return store.restart();
  }

  @Override
  public int traceSize() {
    return trace.size();
  }

  @Override
  public AtomicInstallStep traceStep(int index) {
    return trace.step(index);
  }

  @Override
  public StatusCode traceOutcome(int index) {
    return trace.status(index);
  }

  @Override
  public boolean traceCompletionPending(int index) {
    return trace.completionPending(index);
  }

  @Override
  public StatusCode traceStatus() {
    return store.traceStatus();
  }

  private FaultPoint point(AtomicInstallStep step, FaultBoundary boundary) {
    return switch (step) {
      case TEMP_CREATE -> boundary == FaultBoundary.BEFORE
          ? points.tempCreateBefore()
          : points.tempCreateAfter();
      case TEMP_WRITE -> boundary == FaultBoundary.BEFORE
          ? points.tempWriteBefore()
          : points.tempWriteAfter();
      case TEMP_FORCE -> boundary == FaultBoundary.BEFORE
          ? points.tempForceBefore()
          : points.tempForceAfter();
      case DESTINATION_REPLACE -> boundary == FaultBoundary.BEFORE
          ? points.replaceBefore()
          : points.replaceAfter();
      case PARENT_DIRECTORY_FORCE -> boundary == FaultBoundary.BEFORE
          ? points.directoryForceBefore()
          : points.directoryForceAfter();
      case REOPEN_VERIFY -> boundary == FaultBoundary.BEFORE
          ? points.reopenVerifyBefore()
          : points.reopenVerifyAfter();
      case NONE -> null;
    };
  }

  private static FaultOperation operation(AtomicInstallStep step) {
    return switch (step) {
      case TEMP_CREATE -> FaultOperation.TEMP_CREATE;
      case TEMP_WRITE -> FaultOperation.TEMP_WRITE;
      case TEMP_FORCE -> FaultOperation.TEMP_FORCE;
      case DESTINATION_REPLACE -> FaultOperation.REPLACE;
      case PARENT_DIRECTORY_FORCE -> FaultOperation.DIRECTORY_FORCE;
      case REOPEN_VERIFY -> FaultOperation.REOPEN_VERIFY;
      case NONE -> null;
    };
  }

  private static FaultPoint point(
      FaultPointRegistry registry,
      FaultPointSlot slot,
      String name) {
    StatusCode status = registry.register(name, slot);
    return status.isOk() ? slot.value() : null;
  }
}
