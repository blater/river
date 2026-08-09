package io.riverdb.testkit.io;

/** Creates an isolated provider scenario with fixed fault-rule and trace capacities. */
@FunctionalInterface
public interface AtomicInstallContractProviderFactory {
  AtomicInstallContractProvider create(int ruleCapacity, int traceCapacity);
}
