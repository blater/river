package io.riverdb.engine.testsupport.fault;

import io.riverdb.base.error.StatusCode;

final class FaultingDurableFaultBoundary {
  private final FaultInjector injector;
  private final DirectoryFaultPoints points;
  private final FaultDecision decision = new FaultDecision();
  private long operationSequence;

  FaultingDurableFaultBoundary(FaultInjector injector, DirectoryFaultPoints points) {
    this.injector = injector;
    this.points = points;
  }

  StatusCode before(
      FaultingDurableDirectory directory,
      DirectoryOperation operation,
      long position,
      int requestedBytes) {
    return boundary(directory, operation, FaultBoundary.BEFORE, position, requestedBytes);
  }

  StatusCode after(
      FaultingDurableDirectory directory,
      DirectoryOperation operation,
      long position,
      int requestedBytes) {
    return boundary(directory, operation, FaultBoundary.AFTER, position, requestedBytes);
  }

  FaultAction action() {
    return decision.action();
  }

  long argument() {
    return decision.argument();
  }

  private StatusCode boundary(
      FaultingDurableDirectory directory,
      DirectoryOperation operation,
      FaultBoundary boundary,
      long position,
      int requestedBytes) {
    FaultOperation faultOperation = faultOperation(operation);
    injector.evaluate(
        points.point(operation, boundary),
        faultOperation,
        boundary,
        ++operationSequence,
        position,
        requestedBytes,
        decision);
    FaultAction action = decision.action();
    if (!action.isCompatibleWith(faultOperation, boundary)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (action == FaultAction.DELAY) {
      return boundary == FaultBoundary.BEFORE ? StatusCode.RETRY : StatusCode.OK;
    }
    if (action == FaultAction.CANCEL) {
      return StatusCode.CANCELLED;
    }
    if (action == FaultAction.CRASH) {
      directory.crashFromFault(false);
      return StatusCode.IO_FAILURE;
    }
    if (action == FaultAction.RESTART) {
      directory.crashFromFault(true);
      return StatusCode.CANCELLED;
    }
    return StatusCode.OK;
  }

  private static FaultOperation faultOperation(DirectoryOperation operation) {
    return switch (operation) {
      case CREATE_DIRECTORY -> FaultOperation.DIRECTORY_CREATE;
      case CREATE_FILE -> FaultOperation.FILE_CREATE;
      case LIST -> FaultOperation.DIRECTORY_LIST;
      case RENAME -> FaultOperation.FILE_RENAME;
      case REMOVE -> FaultOperation.FILE_REMOVE;
      case TRUNCATE -> FaultOperation.NAMED_TRUNCATE;
      case FILE_READ -> FaultOperation.DIRECTORY_FILE_READ;
      case FILE_WRITE -> FaultOperation.DIRECTORY_FILE_WRITE;
      case FILE_FORCE -> FaultOperation.DIRECTORY_FILE_FORCE;
      case DIRECTORY_FORCE -> FaultOperation.DIRECTORY_FORCE;
      case REOPEN -> FaultOperation.DIRECTORY_REOPEN;
    };
  }
}
