package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.testkit.crash.CrashPointController;
import java.util.Locale;

/** Deterministic adapter for the reusable general-directory suite. */
public final class FaultingDurableDirectoryContractProvider
    implements DurableDirectoryContractProvider {
  private final CrashPointController controller;
  private final DirectoryFaultPoints points = new DirectoryFaultPoints();
  private final DirectoryOperationTrace trace;
  private final FaultingDurableDirectory directory;

  public FaultingDurableDirectoryContractProvider(int ruleCapacity, int traceCapacity) {
    int pointCount = DirectoryOperation.values().length * FaultBoundary.values().length;
    FaultPointRegistry registry = new FaultPointRegistry(pointCount);
    for (DirectoryOperation operation : DirectoryOperation.values()) {
      for (FaultBoundary boundary : FaultBoundary.values()) {
        FaultPointSlot slot = new FaultPointSlot();
        String name = "directory."
            + operation.name().toLowerCase(Locale.ROOT).replace('_', '-')
            + "."
            + boundary.name().toLowerCase(Locale.ROOT);
        StatusCode status = registry.register(name, slot);
        if (status.isOk()) {
          points.set(operation, boundary, slot.value());
        }
      }
    }
    controller = new CrashPointController(Math.max(0, ruleCapacity));
    trace = new DirectoryOperationTrace(traceCapacity);
    directory = new FaultingDurableDirectory(16, 512, 16, controller, points, trace);
  }

  @Override
  public DurableDirectory directory() {
    return directory;
  }

  @Override
  public StatusCode script(
      DirectoryOperation operation,
      FaultBoundary boundary,
      FaultAction action,
      long argument) {
    if (operation == null || boundary == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return controller.addRule(
        points.point(operation, boundary),
        FaultingDurableDirectory.faultOperation(operation),
        boundary,
        1,
        1,
        action,
        argument);
  }

  @Override
  public StatusCode crash() {
    return directory.crash();
  }

  @Override
  public StatusCode restart() {
    return directory.restart();
  }

  @Override
  public long generation() {
    return directory.generation();
  }

  @Override
  public int traceSize() {
    return trace.size();
  }

  @Override
  public StatusCode traceStatus() {
    return directory.traceStatus();
  }
}
