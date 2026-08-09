package io.riverdb.testkit.io;

/** Factory isolates each provider-neutral scenario and its fault script. */
@FunctionalInterface
public interface DurableDirectoryContractProviderFactory {
  DurableDirectoryContractProvider create(int ruleCapacity, int traceCapacity);
}
