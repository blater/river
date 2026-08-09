package io.riverdb.testkit.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultDecision;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import org.junit.jupiter.api.Test;

final class CrashPointControllerTest {
  @Test
  void resetReplaysTheSameRuleSequence() {
    FaultPoint point = point("wal.after-block-write");
    CrashPointController controller = new CrashPointController(1);
    FaultDecision decision = new FaultDecision();
    assertEquals(
        StatusCode.OK,
        controller.addRule(
            point,
            FaultOperation.WRITE,
            2,
            2,
            FaultAction.PARTIAL_WRITE,
            17));

    FaultAction[] first = observeFour(controller, point, decision);
    controller.reset();
    FaultAction[] second = observeFour(controller, point, decision);

    for (int index = 0; index < first.length; index++) {
      assertEquals(first[index], second[index]);
    }
    assertEquals(FaultAction.NONE, first[0]);
    assertEquals(FaultAction.PARTIAL_WRITE, first[1]);
    assertEquals(FaultAction.PARTIAL_WRITE, first[2]);
    assertEquals(FaultAction.NONE, first[3]);
  }

  @Test
  void ruleTableIsBounded() {
    FaultPoint point = point("page.before-force");
    CrashPointController controller = new CrashPointController(1);
    assertEquals(
        StatusCode.OK,
        controller.addRule(point, FaultOperation.FORCE, 1, 1, FaultAction.FORCE_FAILURE, 0));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        controller.addRule(point, FaultOperation.FORCE, 2, 1, FaultAction.DISK_FULL, 0));
  }

  @Test
  void rejectsActionsThatCannotApplyToTheNamedOperation() {
    FaultPoint point = point("scheduler.before-run");
    CrashPointController controller = new CrashPointController(2);

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        controller.addRule(
            point,
            FaultOperation.RUN_TASK,
            1,
            1,
            FaultAction.SHORT_READ,
            1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        controller.addRule(
            point,
            FaultOperation.WRITE,
            1,
            1,
            FaultAction.FORCE_FAILURE,
            0));
    assertEquals(0, controller.size());
  }

  @Test
  void overlappingRulesAllObserveWhileFirstFiringRuleWins() {
    FaultPoint point = point("file.before-read");
    CrashPointController controller = new CrashPointController(2);
    FaultDecision decision = new FaultDecision();
    assertEquals(
        StatusCode.OK,
        controller.addRule(
            point,
            FaultOperation.READ,
            1,
            1,
            FaultAction.CANCEL,
            0));
    assertEquals(
        StatusCode.OK,
        controller.addRule(
            point,
            FaultOperation.READ,
            1,
            2,
            FaultAction.DETECTED_CORRUPTION,
            1));

    controller.evaluate(point, FaultOperation.READ, 1, 0, 4, decision);
    assertEquals(FaultAction.CANCEL, decision.action());
    controller.evaluate(point, FaultOperation.READ, 2, 0, 4, decision);
    assertEquals(FaultAction.DETECTED_CORRUPTION, decision.action());
  }

  private static FaultAction[] observeFour(
      CrashPointController controller,
      FaultPoint point,
      FaultDecision decision) {
    FaultAction[] actions = new FaultAction[4];
    for (int index = 0; index < actions.length; index++) {
      controller.evaluate(point, FaultOperation.WRITE, index + 1, 0, 32, decision);
      actions[index] = decision.action();
    }
    return actions;
  }

  private static FaultPoint point(String name) {
    FaultPointRegistry registry = new FaultPointRegistry(1);
    FaultPointSlot slot = new FaultPointSlot();
    assertEquals(StatusCode.OK, registry.register(name, slot));
    return slot.value();
  }
}
