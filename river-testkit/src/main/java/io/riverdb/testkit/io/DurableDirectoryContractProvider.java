package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultBoundary;
import io.riverdb.platform.file.DurableDirectory;

/** Adapter implemented by fakes and future physical providers running the shared contract. */
public interface DurableDirectoryContractProvider {
  DurableDirectory directory();

  StatusCode script(
      DirectoryOperation operation,
      FaultBoundary boundary,
      FaultAction action,
      long argument);

  StatusCode crash();

  StatusCode restart();

  long generation();

  int traceSize();

  StatusCode traceStatus();
}
