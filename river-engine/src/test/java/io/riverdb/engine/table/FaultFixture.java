package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.testsupport.fault.CrashPointController;
import io.riverdb.engine.testsupport.fault.DirectoryFaultPoints;
import io.riverdb.engine.testsupport.fault.DirectoryOperation;
import io.riverdb.engine.testsupport.fault.FaultAction;
import io.riverdb.engine.testsupport.fault.FaultBoundary;
import io.riverdb.engine.testsupport.fault.FaultOperation;
import io.riverdb.engine.testsupport.fault.FaultPointRegistry;
import io.riverdb.engine.testsupport.fault.FaultPointSlot;
import io.riverdb.engine.testsupport.fault.FaultingDurableDirectory;

final class FaultFixture {
  final CrashPointController controller = new CrashPointController(1);
  final DirectoryFaultPoints points = new DirectoryFaultPoints();
  final FaultingDurableDirectory directory;

  FaultFixture() {
    FaultPointRegistry registry = new FaultPointRegistry(
        DirectoryOperation.values().length * FaultBoundary.values().length);
    for (DirectoryOperation operation : DirectoryOperation.values()) {
      for (FaultBoundary boundary : FaultBoundary.values()) {
        FaultPointSlot slot = new FaultPointSlot();
        String pointName = "engine-group."
            + operation.name().toLowerCase(java.util.Locale.ROOT)
            + "." + boundary.name().toLowerCase(java.util.Locale.ROOT);
        assertEquals(StatusCode.OK, registry.register(pointName, slot));
        points.set(operation, boundary, slot.value());
      }
    }
    directory = new FaultingDurableDirectory(
        16, 16 * 1024 * 1024, 32, controller, points);
  }

  StatusCode arm(
      DirectoryOperation operation, FaultOperation faultOperation, FaultAction action) {
    return controller.addRule(
        points.point(operation, FaultBoundary.BEFORE), faultOperation,
        FaultBoundary.BEFORE, 1, 1, action, 1);
  }
}
