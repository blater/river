package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseGovernor;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import org.junit.jupiter.api.Test;

final class IndexedTransactionResourceAdmissionTest {
  @Test
  void repeatedAbsoluteWorkspaceAdmissionCannotDoubleChargeAfterAllocationFailure() {
    DatabaseResourceGovernor governor = databaseGovernor(2);
    IndexedTransactionResourceAdmission admission =
        new IndexedTransactionResourceAdmission(governor);

    assertEquals(StatusCode.OK, admission.begin(1));
    assertEquals(StatusCode.OK, admission.ensureWrite(1_000, 1, false));
    assertEquals(1_000, governor.retainedDatabaseAccountedBytes());

    assertEquals(StatusCode.OK, admission.ensureWalRetainedBytes(200));
    assertEquals(1_200, governor.retainedDatabaseAccountedBytes());
    // An allocator may fail after admission. Retrying the same target is idempotent.
    assertEquals(StatusCode.OK, admission.ensureWalRetainedBytes(200));
    assertEquals(1_200, governor.retainedDatabaseAccountedBytes());

    assertEquals(StatusCode.OK, admission.ensureWalRetainedBytes(250));
    assertEquals(1_250, governor.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, admission.end());
    assertEquals(1_250, governor.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, admission.closeSession());
    assertEquals(0, governor.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, governor.close());
  }
}
