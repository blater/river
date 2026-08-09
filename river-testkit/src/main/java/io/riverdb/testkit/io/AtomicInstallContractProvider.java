package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.file.AtomicFileInstaller;
import io.riverdb.platform.file.AtomicInstallStep;
import io.riverdb.platform.file.DurableDirectory;

/** Adapter implemented by every provider running the reusable install contract suite. */
public interface AtomicInstallContractProvider {
  AtomicFileInstaller installer();

  DurableDirectory directory();

  StatusCode script(
      AtomicInstallStep step,
      FaultBoundary boundary,
      FaultAction action,
      long argument);

  StatusCode crash();

  StatusCode restart();

  int traceSize();

  AtomicInstallStep traceStep(int index);

  StatusCode traceOutcome(int index);

  boolean traceCompletionPending(int index);

  StatusCode traceStatus();
}
