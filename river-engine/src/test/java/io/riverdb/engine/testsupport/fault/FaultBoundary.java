package io.riverdb.engine.testsupport.fault;

/** Whether a scripted decision occurs before application or after application before completion. */
public enum FaultBoundary {
  BEFORE,
  AFTER
}
