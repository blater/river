package io.riverdb.journal.api;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.IdempotencyKey;
import io.riverdb.base.id.JournalPosition;
import io.riverdb.journal.api.durability.DurabilityResult;
import io.riverdb.journal.api.durability.DurabilityTicket;
import io.riverdb.journal.api.durability.DurabilityWaitRequest;
import io.riverdb.journal.api.durability.JournalCapabilities;
import io.riverdb.journal.api.frontier.JournalFrontierSnapshot;
import io.riverdb.journal.api.mapping.JournalPositionMapping;
import io.riverdb.journal.api.outcome.RequestOutcomeResult;
import io.riverdb.journal.api.outcome.OutcomeRetentionSnapshot;
import io.riverdb.journal.api.retention.RetentionSnapshot;
import io.riverdb.journal.api.retention.WalRetentionLease;
import io.riverdb.journal.api.retention.WalRetentionLeaseRequest;

/**
 * Provider-independent bounded ordered journal contract. Implementations serialize mutations
 * under their declared owner model. Snapshot, inspection, and outcome lookup operations must
 * return an atomic view and may be called by concurrent readers with distinct output carriers.
 */
public interface JournalProvider {
  DatabaseIncarnation databaseIncarnation();

  NodeIncarnation nodeIncarnation();

  JournalCapabilities capabilities();

  StatusCode reserve(
      JournalReserveRequest request,
      JournalReservation reservation,
      StatusDetail detail);

  StatusCode publish(
      JournalReservation reservation,
      JournalAppendRequest request,
      JournalAppendResult result,
      StatusDetail detail);

  StatusCode cancelReservation(JournalReservation reservation, StatusDetail detail);

  StatusCode beginDurabilityWait(
      DurabilityWaitRequest request,
      DurabilityTicket ticket,
      DurabilityResult result,
      StatusDetail detail);

  StatusCode pollDurability(
      DurabilityTicket ticket,
      long nowNanos,
      CancellationToken cancellation,
      DurabilityResult result,
      StatusDetail detail);

  StatusCode cancelDurabilityWait(
      DurabilityTicket ticket,
      DurabilityResult result,
      StatusDetail detail);

  StatusCode snapshotFrontiers(JournalFrontierSnapshot result);

  StatusCode inspectMapping(JournalPosition position, JournalPositionMapping result);

  StatusCode lookupOutcome(
      DatabaseIncarnation databaseIncarnation,
      NodeIncarnation nodeIncarnation,
      IdempotencyKey idempotencyKey,
      long nowNanos,
      RequestOutcomeResult result,
      StatusDetail detail);

  StatusCode forgetExpiredOutcomes(
      long nowNanos,
      OutcomeRetentionSnapshot result,
      StatusDetail detail);

  StatusCode acquireRetentionLease(
      WalRetentionLeaseRequest request,
      WalRetentionLease lease,
      StatusDetail detail);

  StatusCode renewRetentionLease(
      WalRetentionLease lease,
      WalRetentionLeaseRequest request,
      StatusDetail detail);

  StatusCode reopenRetentionLease(
      DatabaseIncarnation databaseIncarnation,
      NodeIncarnation nodeIncarnation,
      long leaseId,
      long nowNanos,
      WalRetentionLease lease,
      StatusDetail detail);

  StatusCode releaseRetentionLease(
      WalRetentionLease lease,
      StatusDetail detail);

  StatusCode snapshotRetention(long nowNanos, RetentionSnapshot result);
}
