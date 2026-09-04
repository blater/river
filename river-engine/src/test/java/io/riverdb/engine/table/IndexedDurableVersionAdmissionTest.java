package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class IndexedDurableVersionAdmissionTest {
  @Test
  void exactRemainingAddressSpaceIsAdmittedWithoutMaintenance() {
    IndexedDurableVersionAdmission admission = new IndexedDurableVersionAdmission();

    assertEquals(
        StatusCode.OK,
        admission.admit(IndexedTableLimits.MAX_ROWS - 3, 2, 3));
    assertEquals(StatusCode.OK, admission.transactionAdmissionStatus());
  }

  @Test
  void permanentShortfallDoesNotCreateARetryDrain() {
    IndexedDurableVersionAdmission admission = new IndexedDurableVersionAdmission();

    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        admission.admit(IndexedTableLimits.MAX_ROWS - 2, 0, 3));
    assertEquals(StatusCode.OK, admission.transactionAdmissionStatus());
  }

  @Test
  void reclaimableShortfallLatchesAdmissionUntilMaintenanceCreatesSpace() {
    IndexedDurableVersionAdmission admission = new IndexedDurableVersionAdmission();

    assertEquals(
        StatusCode.RETRY,
        admission.admit(IndexedTableLimits.MAX_ROWS - 2, 7, 3));
    assertEquals(StatusCode.RETRY, admission.transactionAdmissionStatus());
    assertEquals(
        StatusCode.RETRY,
        admission.admit(IndexedTableLimits.MAX_ROWS - 20, 7, 1));

    assertEquals(
        StatusCode.OK,
        admission.maintenanceCompleted(IndexedTableLimits.MAX_ROWS - 7, 0));
    assertEquals(StatusCode.OK, admission.transactionAdmissionStatus());
  }

  @Test
  void maintenanceThatMakesNoProgressIsAnInvariantFailureAndKeepsTheDrainLatched() {
    IndexedDurableVersionAdmission admission = new IndexedDurableVersionAdmission();
    assertEquals(
        StatusCode.RETRY,
        admission.admit(IndexedTableLimits.MAX_ROWS, 1, 1));

    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        admission.maintenanceCompleted(IndexedTableLimits.MAX_ROWS, 0));
    assertEquals(StatusCode.RETRY, admission.transactionAdmissionStatus());
  }

  @Test
  void incompleteMaintenanceIsAnInvariantFailureAndKeepsTheDrainLatched() {
    IndexedDurableVersionAdmission admission = new IndexedDurableVersionAdmission();
    assertEquals(
        StatusCode.RETRY,
        admission.admit(IndexedTableLimits.MAX_ROWS - 2, 7, 3));

    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        admission.maintenanceCompleted(IndexedTableLimits.MAX_ROWS - 4, 1));
    assertEquals(StatusCode.RETRY, admission.transactionAdmissionStatus());
  }

  @Test
  void invalidDurableStateIsCorruptionRatherThanCapacityPressure() {
    IndexedDurableVersionAdmission admission = new IndexedDurableVersionAdmission();

    assertEquals(StatusCode.CORRUPTION, admission.admit(-1, 0, 1));
    assertEquals(
        StatusCode.CORRUPTION,
        admission.admit(IndexedTableLimits.MAX_ROWS + 1, 0, 1));
    assertEquals(StatusCode.CORRUPTION, admission.admit(1, 2, 1));
  }
}
