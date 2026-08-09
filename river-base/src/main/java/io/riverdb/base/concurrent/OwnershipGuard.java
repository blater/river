package io.riverdb.base.concurrent;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.atomic.AtomicLong;

/** Optional diagnostic owner token for explicit transfer/release lifetimes. */
public final class OwnershipGuard {
  public static final long RELEASED = 0;
  private static final OwnershipGuard DISABLED = new OwnershipGuard(false, RELEASED);

  private final boolean enabled;
  private final AtomicLong owner;

  private OwnershipGuard(boolean enabled, long initialOwner) {
    this.enabled = enabled;
    owner = new AtomicLong(initialOwner);
  }

  public static OwnershipGuard ownedBy(long initialOwner) {
    if (initialOwner == RELEASED) {
      throw new IllegalArgumentException("owner token zero is reserved for released ownership");
    }
    return new OwnershipGuard(true, initialOwner);
  }

  public static OwnershipGuard disabled() {
    return DISABLED;
  }

  public StatusCode checkOwnedBy(long expectedOwner) {
    if (expectedOwner == RELEASED) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return !enabled || owner.get() == expectedOwner ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  public StatusCode transfer(long expectedOwner, long newOwner) {
    if (expectedOwner == RELEASED || newOwner == RELEASED) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (!enabled) {
      return StatusCode.OK;
    }
    return owner.compareAndSet(expectedOwner, newOwner) ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  public StatusCode release(long expectedOwner) {
    if (expectedOwner == RELEASED) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (!enabled) {
      return StatusCode.OK;
    }
    return owner.compareAndSet(expectedOwner, RELEASED) ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  public boolean isReleased() {
    return enabled && owner.get() == RELEASED;
  }
}
