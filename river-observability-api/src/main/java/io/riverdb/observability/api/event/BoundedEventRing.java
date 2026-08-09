package io.riverdb.observability.api.event;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Preallocated bounded multi-producer/single-consumer diagnostic ring.
 *
 * <p>Publication copies into a claimed slot and never waits for capacity. A finite claim retry
 * bound also prevents a producer from spinning indefinitely under contention. Saturated events
 * are accounted for according to the configured policy; none of these outcomes may affect
 * database correctness.
 *
 * <p>The MPSC algorithm publishes in claimed-position order. A preempted producer therefore
 * creates a bounded publication hole: the consumer reports {@link EventPollResult#PUBLICATION_HOLE}
 * until that producer resumes. If a producer terminates after claiming a slot, this best-effort
 * diagnostic ring cannot repair the abandoned slot. Hole observations plus eventual saturation
 * counters expose that condition; database correctness must never depend on this queue making
 * progress.
 */
public final class BoundedEventRing implements DiagnosticSink {
  public static final int MAX_CAPACITY = 1 << 20;
  private static final int MAX_CLAIM_ATTEMPTS = 32;

  private final DiagnosticEvent[] slots;
  private final AtomicLongArray slotSequences;
  private final int mask;
  private final int capacity;
  private final SaturationPolicy saturationPolicy;
  private final ConsumerAccess consumerAccess;
  private final PublicationClaimObserver claimObserver;
  private final AtomicLong producerPosition = new AtomicLong();
  private final AtomicLong publishedCount = new AtomicLong();
  private final AtomicLong droppedCount = new AtomicLong();
  private final AtomicLong backpressureCount = new AtomicLong();
  private final AtomicLong publicationHoleObservationCount = new AtomicLong();
  private final AtomicLong consumerMisuseCount = new AtomicLong();
  private final AtomicLong consumerThreadId = new AtomicLong();
  private volatile long consumerPosition;
  private volatile Severity threshold;
  private volatile boolean enabled = true;

  BoundedEventRing(
      int requestedCapacity,
      Severity initialThreshold,
      SaturationPolicy newSaturationPolicy) {
    this(
        requestedCapacity,
        initialThreshold,
        newSaturationPolicy,
        ConsumerAccess.UNCHECKED,
        PublicationClaimObserver.NO_OP);
  }

  BoundedEventRing(
      int requestedCapacity,
      Severity initialThreshold,
      SaturationPolicy newSaturationPolicy,
      ConsumerAccess newConsumerAccess) {
    this(
        requestedCapacity,
        initialThreshold,
        newSaturationPolicy,
        newConsumerAccess,
        PublicationClaimObserver.NO_OP);
  }

  BoundedEventRing(
      int requestedCapacity,
      Severity initialThreshold,
      SaturationPolicy newSaturationPolicy,
      ConsumerAccess newConsumerAccess,
      PublicationClaimObserver newClaimObserver) {
    if (requestedCapacity < 2
        || requestedCapacity > MAX_CAPACITY
        || Integer.bitCount(requestedCapacity) != 1) {
      throw new IllegalArgumentException(
          "event ring capacity must be a power of two between 2 and MAX_CAPACITY");
    }
    capacity = requestedCapacity;
    mask = capacity - 1;
    threshold = initialThreshold;
    saturationPolicy = newSaturationPolicy;
    consumerAccess = newConsumerAccess;
    claimObserver = newClaimObserver;
    slots = new DiagnosticEvent[capacity];
    slotSequences = new AtomicLongArray(capacity);
    for (int index = 0; index < capacity; index++) {
      slots[index] = new DiagnosticEvent();
      slotSequences.set(index, index);
    }
  }

  @Override
  public boolean isEnabled(Severity severity) {
    return enabled && severity.isEnabledAt(threshold);
  }

  public void enabled(boolean newEnabled) {
    enabled = newEnabled;
  }

  public void threshold(Severity newThreshold) {
    threshold = newThreshold;
  }

  public Severity threshold() {
    return threshold;
  }

  @Override
  public EventPublishResult publish(DiagnosticEvent event) {
    if (!isEnabled(event.severity())) {
      return EventPublishResult.DISABLED;
    }

    int attempts = 0;
    while (attempts++ < MAX_CLAIM_ATTEMPTS) {
      long position = producerPosition.get();
      int slotIndex = (int) position & mask;
      long availableSequence = slotSequences.get(slotIndex);
      long difference = availableSequence - position;
      if (difference < 0) {
        return onSaturation();
      }
      if (difference == 0 && producerPosition.compareAndSet(position, position + 1)) {
        if (claimObserver != PublicationClaimObserver.NO_OP) {
          claimObserver.afterClaim(position);
        }
        slots[slotIndex].copyFrom(event);
        slotSequences.set(slotIndex, position + 1);
        publishedCount.incrementAndGet();
        return EventPublishResult.PUBLISHED;
      }
    }

    backpressureCount.incrementAndGet();
    return EventPublishResult.BACKPRESSURE;
  }

  /**
   * Copies the next event into caller-owned storage. Exactly one consumer may invoke this method.
   */
  public EventPollResult poll(DiagnosticEvent target) {
    if (!isConsumerThread()) {
      consumerMisuseCount.incrementAndGet();
      return EventPollResult.NOT_CONSUMER;
    }
    long position = consumerPosition;
    int slotIndex = (int) position & mask;
    long publishedSequence = slotSequences.get(slotIndex);
    if (publishedSequence != position + 1) {
      if (producerPosition.get() > position) {
        publicationHoleObservationCount.incrementAndGet();
        return EventPollResult.PUBLICATION_HOLE;
      }
      return EventPollResult.EMPTY;
    }
    target.copyFrom(slots[slotIndex]);
    slotSequences.set(slotIndex, position + capacity);
    consumerPosition = position + 1;
    return EventPollResult.POLLED;
  }

  public int capacity() {
    return capacity;
  }

  public ConsumerAccess consumerAccess() {
    return consumerAccess;
  }

  /**
   * Approximate number of claimed but not consumed positions. This includes unpublished holes and
   * may change immediately under concurrent producers.
   */
  public long approximateSize() {
    return producerPosition.get() - consumerPosition;
  }

  public long publishedCount() {
    return publishedCount.get();
  }

  public long droppedCount() {
    return droppedCount.get();
  }

  public long backpressureCount() {
    return backpressureCount.get();
  }

  /** Number of polls that observed a claimed but not yet published head position. */
  public long publicationHoleObservationCount() {
    return publicationHoleObservationCount.get();
  }

  public long consumerMisuseCount() {
    return consumerMisuseCount.get();
  }

  private EventPublishResult onSaturation() {
    return switch (saturationPolicy) {
      case DROP_AND_COUNT -> {
        droppedCount.incrementAndGet();
        yield EventPublishResult.DROPPED;
      }
      case REPORT_BACKPRESSURE -> {
        backpressureCount.incrementAndGet();
        yield EventPublishResult.BACKPRESSURE;
      }
    };
  }

  private boolean isConsumerThread() {
    if (consumerAccess == ConsumerAccess.UNCHECKED) {
      return true;
    }
    long currentThreadId = Thread.currentThread().threadId();
    long ownerThreadId = consumerThreadId.get();
    if (ownerThreadId == currentThreadId) {
      return true;
    }
    return ownerThreadId == 0 && consumerThreadId.compareAndSet(0, currentThreadId);
  }
}
