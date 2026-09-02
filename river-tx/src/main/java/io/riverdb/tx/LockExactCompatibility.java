package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockMode;

/** Single compatibility policy shared by exact scheduling, upgrades, and deadlock edges. */
final class LockExactCompatibility {
  private LockExactCompatibility() { }

  static boolean conflicts(int left, int right) {
    if (left == LockMode.SHARED.ordinal()) return right == LockMode.EXCLUSIVE.ordinal();
    if (left == LockMode.UPDATE.ordinal()) return right != LockMode.SHARED.ordinal();
    return true;
  }

  static boolean grantable(int mode, long owners, long shared, long updates) {
    if (mode == LockMode.SHARED.ordinal()) return owners == shared + updates;
    if (mode == LockMode.UPDATE.ordinal()) return owners == shared && updates == 0;
    return owners == 0;
  }

  static boolean upgradeable(int mode, long owners, long shared, long updates) {
    return mode == LockMode.UPDATE.ordinal()
        ? owners == shared && updates == 0 : owners == 1;
  }
}
