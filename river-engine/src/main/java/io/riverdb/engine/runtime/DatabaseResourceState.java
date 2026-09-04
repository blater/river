package io.riverdb.engine.runtime;

/** Primitive live accounting owned by one database governor monitor. */
final class DatabaseResourceState {
  long accountedBytes;
  long writeEntries;
  long stagedPages;
  long versionOperations;
  long walBytes;
  int leases;

  boolean everFits(
      DatabaseResourcePlan plan, long accountedCapacity, ResourceDemand demand) {
    return demand.accountedBytes() <= accountedCapacity
        && demand.writeEntries() <= plan.writeEntryCapacity()
        && demand.stagedPages() <= plan.stagedPageCapacity()
        && demand.versionOperations() <= plan.versionOperationCapacity()
        && demand.walBytes() <= plan.walByteCapacity();
  }

  boolean available(
      DatabaseResourcePlan plan, long accountedCapacity, ResourceDemand demand) {
    return leases < plan.maximumOwners()
        && demand.accountedBytes() <= accountedCapacity - accountedBytes
        && demand.writeEntries() <= plan.writeEntryCapacity() - writeEntries
        && demand.stagedPages() <= plan.stagedPageCapacity() - stagedPages
        && demand.versionOperations()
            <= plan.versionOperationCapacity() - versionOperations
        && demand.walBytes() <= plan.walByteCapacity() - walBytes;
  }

  boolean growthAvailable(
      DatabaseResourcePlan plan, long accountedCapacity,
      ResourceLease lease, ResourceDemand total) {
    return total.accountedBytes() - lease.accountedBytes()
            <= accountedCapacity - accountedBytes
        && total.writeEntries() - lease.writeEntries()
            <= plan.writeEntryCapacity() - writeEntries
        && total.stagedPages() - lease.stagedPages()
            <= plan.stagedPageCapacity() - stagedPages
        && total.versionOperations() - lease.versionOperations()
            <= plan.versionOperationCapacity() - versionOperations
        && total.walBytes() - lease.walBytes()
            <= plan.walByteCapacity() - walBytes;
  }

  void add(ResourceDemand demand) {
    accountedBytes += demand.accountedBytes();
    writeEntries += demand.writeEntries();
    stagedPages += demand.stagedPages();
    versionOperations += demand.versionOperations();
    walBytes += demand.walBytes();
    leases++;
  }

  void add(ResourceLease lease) {
    accountedBytes += lease.accountedBytes();
    writeEntries += lease.writeEntries();
    stagedPages += lease.stagedPages();
    versionOperations += lease.versionOperations();
    walBytes += lease.walBytes();
    leases++;
  }

  void addGrowth(ResourceLease lease, ResourceDemand total) {
    accountedBytes += total.accountedBytes() - lease.accountedBytes();
    writeEntries += total.writeEntries() - lease.writeEntries();
    stagedPages += total.stagedPages() - lease.stagedPages();
    versionOperations += total.versionOperations() - lease.versionOperations();
    walBytes += total.walBytes() - lease.walBytes();
  }

  boolean contains(ResourceLease lease) {
    return leases > 0 && lease.accountedBytes() <= accountedBytes
        && lease.writeEntries() <= writeEntries
        && lease.stagedPages() <= stagedPages
        && lease.versionOperations() <= versionOperations
        && lease.walBytes() <= walBytes;
  }

  void remove(ResourceLease lease) {
    accountedBytes -= lease.accountedBytes();
    writeEntries -= lease.writeEntries();
    stagedPages -= lease.stagedPages();
    versionOperations -= lease.versionOperations();
    walBytes -= lease.walBytes();
    leases--;
  }

  boolean empty() {
    return leases == 0 && accountedBytes == 0 && writeEntries == 0
        && stagedPages == 0 && versionOperations == 0 && walBytes == 0;
  }

  void reset() {
    accountedBytes = writeEntries = stagedPages = versionOperations = walBytes = 0;
    leases = 0;
  }
}
