package io.riverdb.engine;

import io.riverdb.tx.LockDeadlockDiagnosticsSnapshot;

/** Formats one cold deadlock diagnostics snapshot. */
final class EmbeddedDeadlockDiagnostics {
  private EmbeddedDeadlockDiagnostics() { }

  static void append(StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    appendDiagnosticSummary(target, snapshot);
    appendDiagnosticSignatures(target, snapshot);
    appendDiagnosticEvents(target, snapshot);
    appendDiagnosticExemplars(target, snapshot);
  }

  private static void appendDiagnosticSummary(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    target.append("server_deadlock_diagnostics_enabled=").append(snapshot.config().enabled()).append('\n')
        .append("server_deadlock_diagnostics_budget_bytes=")
        .append(snapshot.config().maximumRetainedBytes()).append('\n')
        .append("server_deadlock_diagnostics_retained_payload_bytes=")
        .append(snapshot.config().retainedPayloadBytes()).append('\n')
        .append("server_deadlock_diagnostics_maximum_epochs=")
        .append(snapshot.config().maximumEpochs()).append('\n')
        .append("server_deadlock_diagnostics_signatures_per_epoch=")
        .append(snapshot.config().signaturesPerEpoch()).append('\n')
        .append("server_deadlock_diagnostics_events_per_epoch=")
        .append(snapshot.config().victimEventsPerEpoch()).append('\n')
        .append("server_deadlock_diagnostics_exemplars_per_signature=")
        .append(snapshot.config().exemplarsPerSignature()).append('\n')
        .append("server_deadlock_diagnostics_maximum_cycle_edges=")
        .append(snapshot.config().maximumCycleEdges()).append('\n')
        .append("server_deadlock_fingerprint_version=")
        .append(LockDeadlockDiagnosticsSnapshot.FINGERPRINT_VERSION).append('\n')
        .append("server_deadlock_diagnostics_valid=")
        .append(snapshot.validForDiagnosticGate()).append('\n')
        .append("server_deadlock_victim_selections=")
        .append(snapshot.counters().totalVictimSelections()).append('\n')
        .append("server_deadlock_victim_outcomes=")
        .append(snapshot.counters().victimTransactionOutcomes()).append('\n')
        .append("server_deadlock_queued_requests_cancelled=")
        .append(snapshot.counters().queuedRequestsCancelled()).append('\n')
        .append("server_deadlock_holdings_released=")
        .append(snapshot.counters().holdingsReleased()).append('\n')
        .append("server_deadlock_self_validation_failures=")
        .append(snapshot.counters().selfValidationFailures()).append('\n')
        .append("server_deadlock_fingerprint_overflows=")
        .append(snapshot.counters().fingerprintOverflows()).append('\n')
        .append("server_deadlock_fingerprint_collisions=")
        .append(snapshot.counters().fingerprintCollisions()).append('\n')
        .append("server_deadlock_epoch_overflows=")
        .append(snapshot.counters().epochOverflows()).append('\n')
        .append("server_deadlock_event_overflows=")
        .append(snapshot.counters().victimEventOverflows()).append('\n')
        .append("server_deadlock_exemplar_overflows=")
        .append(snapshot.counters().exemplarOverflows()).append('\n')
        .append("server_deadlock_cycle_edge_overflows=")
        .append(snapshot.counters().cycleEdgeOverflows()).append('\n')
        .append("server_deadlock_sequence_overflows=")
        .append(snapshot.counters().eventSequenceOverflows()).append('\n');
  }

  private static void appendDiagnosticSignatures(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int index = 0; index < snapshot.counters().signatureCount(); index++) {
      target.append("deadlock_signature index=").append(index)
          .append(" epoch=").append(snapshot.signatures().epochAt(index))
          .append(" fingerprint=")
          .append(Long.toUnsignedString(snapshot.signatures().fingerprintAt(index), 16))
          .append(" collision_guard=")
          .append(Long.toUnsignedString(snapshot.signatures().collisionGuardAt(index), 16))
          .append(" victims=").append(snapshot.signatures().victimSelectionsAt(index))
          .append(" outcomes=").append(snapshot.signatures().victimOutcomesAt(index))
          .append(" queued_cancelled=")
          .append(snapshot.signatures().queuedRequestsCancelledAt(index))
          .append(" holdings_released=")
          .append(snapshot.signatures().holdingsReleasedAt(index))
          .append(" first_sequence=")
          .append(snapshot.signatures().firstEventSequenceAt(index))
          .append(" last_sequence=")
          .append(snapshot.signatures().lastEventSequenceAt(index))
          .append(" exemplars=").append(snapshot.signatures().exemplarCountAt(index))
          .append('\n');
    }
  }

  private static void appendDiagnosticEvents(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int index = 0; index < snapshot.counters().victimEventCount(); index++) {
      target.append("deadlock_event index=").append(index)
          .append(" epoch=").append(snapshot.events().epochAt(index))
          .append(" sequence=").append(snapshot.events().sequenceAt(index))
          .append(" outcome_sequence=").append(snapshot.events().outcomeSequenceAt(index))
          .append(" victim_sequence=")
          .append(snapshot.events().victimSelectionSequenceAt(index))
          .append(" fingerprint=")
          .append(Long.toUnsignedString(snapshot.events().fingerprintAt(index), 16))
          .append(" transaction_id=").append(snapshot.events().transactionIdAt(index))
          .append(" generation=").append(snapshot.events().transactionGenerationAt(index))
          .append(" start_order=").append(snapshot.events().transactionStartOrderAt(index))
          .append(" attempt_tag=").append(snapshot.events().diagnosticTagAt(index))
          .append(" step_tag=").append(snapshot.events().diagnosticStepTagAt(index))
          .append(" outcome=").append(snapshot.events().outcomeStatusAt(index))
          .append(" queued_cancelled=")
          .append(snapshot.events().queuedRequestsCancelledAt(index))
          .append(" holdings_released=").append(snapshot.events().holdingsReleasedAt(index))
          .append(" cleanup_valid=").append(snapshot.events().cleanupValidAt(index))
          .append('\n');
    }
  }

  private static void appendDiagnosticExemplars(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int exemplar = 0; exemplar < snapshot.counters().exemplarCount(); exemplar++) {
      int edges = snapshot.exemplars().edgeCountAt(exemplar);
      target.append("deadlock_exemplar index=").append(exemplar)
          .append(" signature_index=").append(snapshot.exemplars().signatureIndexAt(exemplar))
          .append(" event_index=").append(snapshot.exemplars().eventIndexAt(exemplar))
          .append(" edges=").append(edges).append('\n');
      for (int offset = 0; offset < edges; offset++) {
        int edge = snapshot.exemplars().edgeIndex(exemplar, offset);
        target.append("deadlock_edge exemplar=").append(exemplar)
            .append(" offset=").append(offset)
            .append(" kind=").append(snapshot.edges().kindAt(edge))
            .append(" precondition=").append(snapshot.edges().preconditionAt(edge))
            .append(" grant_predicate=").append(snapshot.edges().grantPredicateResultAt(edge))
            .append(" waiter_attempt_tag=")
            .append(snapshot.edges().waiterDiagnosticTagAt(edge))
            .append(" waiter_step_tag=")
            .append(snapshot.edges().waiterDiagnosticStepTagAt(edge))
            .append(" blocker_attempt_tag=")
            .append(snapshot.edges().blockerDiagnosticTagAt(edge))
            .append(" blocker_step_tag=")
            .append(snapshot.edges().blockerDiagnosticStepTagAt(edge))
            .append(" scope=").append(snapshot.edges().resourceScopeAt(edge))
            .append(" requested_mode=").append(snapshot.edges().requestedModeAt(edge))
            .append(" held_mode=").append(snapshot.edges().heldModeAt(edge))
            .append(" blocker_requested_mode=")
            .append(snapshot.edges().blockerRequestedModeAt(edge))
            .append(" waiter_queue=").append(snapshot.edges().waiterQueueKindAt(edge))
            .append(" waiter_order=").append(snapshot.edges().waiterQueueOrderAt(edge))
            .append(" blocker_queue=").append(snapshot.edges().blockerQueueKindAt(edge))
            .append(" blocker_order=").append(snapshot.edges().blockerQueueOrderAt(edge))
            .append(" resource_namespace=").append(snapshot.edges().resourceNamespaceAt(edge))
            .append(" resource_lower=").append(snapshot.edges().resourceLowerKeyAt(edge))
            .append(" resource_upper_namespace=")
            .append(snapshot.edges().resourceUpperNamespaceAt(edge))
            .append(" resource_upper=").append(snapshot.edges().resourceUpperKeyAt(edge))
            .append('\n');
      }
    }
  }
}
