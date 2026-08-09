package io.riverdb.observability.api.event;

/** Package-private deterministic test seam for publication-hole behavior. */
@FunctionalInterface
interface PublicationClaimObserver {
  PublicationClaimObserver NO_OP = position -> {
  };

  void afterClaim(long position);
}
