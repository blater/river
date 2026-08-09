package io.riverdb.journal.api.durability;

/** Named acknowledgement contract; providers must reject unsupported values before reservation. */
public enum DurabilityRequirement {
  LOCAL_DURABLE,
  QUORUM_DURABLE,
  QUORUM_ACCEPTED
}
