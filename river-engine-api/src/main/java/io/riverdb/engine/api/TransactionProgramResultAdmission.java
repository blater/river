package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Pre-commit owner boundary for storage needed to publish a program result. */
public interface TransactionProgramResultAdmission {
  StatusCode admit(TransactionProgramResult result);
}
