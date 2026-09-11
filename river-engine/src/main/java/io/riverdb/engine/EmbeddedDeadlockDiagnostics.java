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
    target.append("server_deadlock_diagnostics_enabled=").append(snapshot.enabled()).append('\n')
        .append("server_deadlock_diagnostics_budget_bytes=")
        .append(snapshot.maximumRetainedBytes()).append('\n')
        .append("server_deadlock_diagnostics_retained_payload_bytes=")
        .append(snapshot.retainedPayloadBytes()).append('\n')
        .append("server_deadlock_diagnostics_maximum_epochs=")
        .append(snapshot.maximumEpochs()).append('\n')
        .append("server_deadlock_diagnostics_signatures_per_epoch=")
        .append(snapshot.signaturesPerEpoch()).append('\n')
        .append("server_deadlock_diagnostics_events_per_epoch=")
        .append(snapshot.victimEventsPerEpoch()).append('\n')
        .append("server_deadlock_diagnostics_exemplars_per_signature=")
        .append(snapshot.exemplarsPerSignature()).append('\n')
        .append("server_deadlock_diagnostics_maximum_cycle_edges=")
        .append(snapshot.maximumCycleEdges()).append('\n')
        .append("server_deadlock_fingerprint_version=")
        .append(snapshot.fingerprintVersion()).append('\n')
        .append("server_deadlock_diagnostics_valid=")
        .append(snapshot.validForDiagnosticGate()).append('\n')
        .append("server_deadlock_victim_selections=")
        .append(snapshot.totalVictimSelections()).append('\n')
        .append("server_deadlock_victim_outcomes=")
        .append(snapshot.victimTransactionOutcomes()).append('\n')
        .append("server_deadlock_queued_requests_cancelled=")
        .append(snapshot.queuedRequestsCancelled()).append('\n')
        .append("server_deadlock_holdings_released=")
        .append(snapshot.holdingsReleased()).append('\n')
        .append("server_deadlock_self_validation_failures=")
        .append(snapshot.selfValidationFailures()).append('\n')
        .append("server_deadlock_fingerprint_overflows=")
        .append(snapshot.fingerprintOverflows()).append('\n')
        .append("server_deadlock_fingerprint_collisions=")
        .append(snapshot.fingerprintCollisions()).append('\n')
        .append("server_deadlock_epoch_overflows=")
        .append(snapshot.epochOverflows()).append('\n')
        .append("server_deadlock_event_overflows=")
        .append(snapshot.victimEventOverflows()).append('\n')
        .append("server_deadlock_exemplar_overflows=")
        .append(snapshot.exemplarOverflows()).append('\n')
        .append("server_deadlock_cycle_edge_overflows=")
        .append(snapshot.cycleEdgeOverflows()).append('\n')
        .append("server_deadlock_sequence_overflows=")
        .append(snapshot.eventSequenceOverflows()).append('\n');
  }

  private static void appendDiagnosticSignatures(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int index = 0; index < snapshot.signatureCount(); index++) {
      target.append("deadlock_signature index=").append(index)
          .append(" epoch=").append(snapshot.signatureEpochAt(index))
          .append(" fingerprint=")
          .append(Long.toUnsignedString(snapshot.fingerprintAt(index), 16))
          .append(" collision_guard=")
          .append(Long.toUnsignedString(snapshot.collisionGuardAt(index), 16))
          .append(" victims=").append(snapshot.signatureVictimSelectionsAt(index))
          .append(" outcomes=").append(snapshot.signatureVictimOutcomesAt(index))
          .append(" queued_cancelled=")
          .append(snapshot.signatureQueuedRequestsCancelledAt(index))
          .append(" holdings_released=")
          .append(snapshot.signatureHoldingsReleasedAt(index))
          .append(" first_sequence=")
          .append(snapshot.signatureFirstEventSequenceAt(index))
          .append(" last_sequence=")
          .append(snapshot.signatureLastEventSequenceAt(index))
          .append(" exemplars=").append(snapshot.signatureExemplarCountAt(index))
          .append('\n');
    }
  }

  private static void appendDiagnosticEvents(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int index = 0; index < snapshot.victimEventCount(); index++) {
      target.append("deadlock_event index=").append(index)
          .append(" epoch=").append(snapshot.eventEpochAt(index))
          .append(" sequence=").append(snapshot.eventSequenceAt(index))
          .append(" outcome_sequence=").append(snapshot.eventOutcomeSequenceAt(index))
          .append(" victim_sequence=")
          .append(snapshot.eventVictimSelectionSequenceAt(index))
          .append(" fingerprint=")
          .append(Long.toUnsignedString(snapshot.eventFingerprintAt(index), 16))
          .append(" transaction_id=").append(snapshot.eventTransactionIdAt(index))
          .append(" generation=").append(snapshot.eventTransactionGenerationAt(index))
          .append(" start_order=").append(snapshot.eventTransactionStartOrderAt(index))
          .append(" attempt_tag=").append(snapshot.eventDiagnosticTagAt(index))
          .append(" step_tag=").append(snapshot.eventDiagnosticStepTagAt(index))
          .append(" outcome=").append(snapshot.eventOutcomeStatusAt(index))
          .append(" queued_cancelled=")
          .append(snapshot.eventQueuedRequestsCancelledAt(index))
          .append(" holdings_released=").append(snapshot.eventHoldingsReleasedAt(index))
          .append(" cleanup_valid=").append(snapshot.eventCleanupValidAt(index))
          .append('\n');
    }
  }

  private static void appendDiagnosticExemplars(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int exemplar = 0; exemplar < snapshot.exemplarCount(); exemplar++) {
      int edges = snapshot.exemplarEdgeCountAt(exemplar);
      target.append("deadlock_exemplar index=").append(exemplar)
          .append(" signature_index=").append(snapshot.exemplarSignatureIndexAt(exemplar))
          .append(" event_index=").append(snapshot.exemplarEventIndexAt(exemplar))
          .append(" edges=").append(edges).append('\n');
      for (int offset = 0; offset < edges; offset++) {
        int edge = snapshot.exemplarEdgeIndex(exemplar, offset);
        target.append("deadlock_edge exemplar=").append(exemplar)
            .append(" offset=").append(offset)
            .append(" kind=").append(snapshot.edgeKindAt(edge))
            .append(" precondition=").append(snapshot.edgePreconditionAt(edge))
            .append(" grant_predicate=").append(snapshot.edgeGrantPredicateResultAt(edge))
            .append(" waiter_attempt_tag=")
            .append(snapshot.edgeWaiterDiagnosticTagAt(edge))
            .append(" waiter_step_tag=")
            .append(snapshot.edgeWaiterDiagnosticStepTagAt(edge))
            .append(" blocker_attempt_tag=")
            .append(snapshot.edgeBlockerDiagnosticTagAt(edge))
            .append(" blocker_step_tag=")
            .append(snapshot.edgeBlockerDiagnosticStepTagAt(edge))
            .append(" scope=").append(snapshot.edgeResourceScopeAt(edge))
            .append(" requested_mode=").append(snapshot.edgeRequestedModeAt(edge))
            .append(" held_mode=").append(snapshot.edgeHeldModeAt(edge))
            .append(" blocker_requested_mode=")
            .append(snapshot.edgeBlockerRequestedModeAt(edge))
            .append(" waiter_queue=").append(snapshot.edgeWaiterQueueKindAt(edge))
            .append(" waiter_order=").append(snapshot.edgeWaiterQueueOrderAt(edge))
            .append(" blocker_queue=").append(snapshot.edgeBlockerQueueKindAt(edge))
            .append(" blocker_order=").append(snapshot.edgeBlockerQueueOrderAt(edge))
            .append(" resource_namespace=").append(snapshot.edgeResourceNamespaceAt(edge))
            .append(" resource_lower=").append(snapshot.edgeResourceLowerKeyAt(edge))
            .append(" resource_upper_namespace=")
            .append(snapshot.edgeResourceUpperNamespaceAt(edge))
            .append(" resource_upper=").append(snapshot.edgeResourceUpperKeyAt(edge))
            .append('\n');
      }
    }
  }
}
