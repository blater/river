package io.riverdb.testkit.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class DurableDirectoryContractSuiteTest {
  @Test
  void faultingProviderPassesReusableDirectoryContract() {
    DurableDirectoryContractSuite suite = new DurableDirectoryContractSuite();
    DurableDirectorySuiteResult result = new DurableDirectorySuiteResult();

    assertEquals(
        StatusCode.OK,
        suite.run(FaultingDurableDirectoryContractProvider::new, result));
    assertEquals(StatusCode.OK, result.status());
    assertEquals(0, result.failedScenario());
    assertEquals(8, result.completedScenarios());
  }
}
