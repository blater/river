package io.riverdb.testkit.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class AtomicFileInstallerContractSuiteTest {
  @Test
  void faultingProviderPassesTheReusableProviderSuite() {
    AtomicFileInstallerContractSuite suite = new AtomicFileInstallerContractSuite();
    AtomicInstallSuiteResult result = new AtomicInstallSuiteResult();

    assertEquals(
        StatusCode.OK,
        suite.run(FaultingAtomicInstallContractProvider::new, result));
    assertEquals(StatusCode.OK, result.status());
    assertEquals(0, result.scenario());
    assertEquals(7, result.completedScenarios());
  }
}
