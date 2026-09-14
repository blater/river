# Checkpoint stall: one call-site hypothesis

Owner: [tic-osgiliath](../tickets/tic-osgiliath.md). Historical evidence and the
existing run recipe remain in
[the kernel-hang investigation](checkpoint-kernel-hang-20260913.md),
[tic-emeldir](../tickets/tic-emeldir.md), and
[the capture plan](checkpoint-crash-reproduction.md). This plan adds one bounded
question; it does not replace those records. The user authorized the later
risk-bearing attempt on this Mac, subject to the mandatory preflight gate below.

## The one question

For the next observed checkpoint incident, is the `FileChannel.write` stalled in
the macOS `VNOP_WRITE` path the one-byte call initiated by
`NioDurableFile.resize`, rather than an ordinary positional call initiated by
`NioDurableFile.write`?

The test hypothesis is **`RESIZE_GROWTH`**. This is a call-site hypothesis. An
ordinary positional page write can also extend EOF, so the alternative does not
mean overwrite, and `cluster_write_ext` does not identify file extension. The
result will select the exact River implementation for a later minimized
reproducer. It will not establish that growth caused the kernel stall, explain
the three historical incidents, or prove an XNU/APFS defect.

The current source preserves the premise: both call sites enter
`NioDurableFile.writeChannel`; only the resize branch writes one byte at
`sizeBytes - 1`. The accepted pending-write event records path, handle, position,
requested count, Java writer, and monotonic start, but it does not record which
call site invoked the helper. Publication occurs before `FileChannel.write`, so
the event alone does not prove native entry.

## Smallest instrumentation change

Extend the existing opt-in diagnostic only:

1. Pass an `operationKind` constant into `writeChannel`: `POSITIONAL_WRITE` from
   `write` and `RESIZE_GROWTH` from the growth branch in `resize`.
2. Carry that constant in the existing immutable pending invocation and emit it
   on `river.PendingFileWrite`.
3. Extend the existing downstream-gate test to assert both call sites by their
   declared kind. Retain the current overlap, retirement, and disabled-mode
   behavior.

Do not record `fileSizeBefore`: a separate `channel.size()` observation would
race other writes and is unnecessary. Do not infer the kind from count `1`,
position, path, or filename. Do not add another event, file-I/O wrapper, tracing
framework, provider, retry loop, or production scheduling hook.

## Native-attribution preflight: mandatory gate

Before the incident workload, prove on this Mac that one passive collector can
preserve a same-thread mixed stack from a representative `river-connection-*`
virtual-thread writer through the JDK native `FileChannel.write` bridge to its
mounted carrier OS thread. A platform-thread demonstration is insufficient. The
existing forwarding gate is before the delegate and cannot prove this.

Use a controlled, single-writer regular-file case for each operation kind, run
through the same server virtual-thread execution path as CHECKPOINT. With
pending-write diagnostics enabled, collect native/mixed stacks and a JFR snapshot
without suspending the JVM or preventing the other collectors from running.
Acceptance requires one same-thread chain containing the known River call site,
the mounted virtual-thread work, `FileChannel.write`, and its JDK native bridge,
plus a pending event whose kind, path, position, count, and Java writer match the
controlled call. Record the exact collector command and the thread identifiers
each tool assigns. This demonstrates the attribution bridge only; seeing the JDK
bridge in preflight does not prove that a call entered or blocked in the kernel.

This preflight specifically rejects these substitutes:

- equality between a Java or virtual-thread ID and a kernel thread ID;
- the JFR periodic event's emitting thread;
- a carrier name without a same-thread mounted stack;
- `lsof` inventory or a completed `fs_usage` call;
- a pending event published before native entry.

No currently retained artifact demonstrates that join. If this Mac's JDK and
available passive collector cannot expose it for both known call sites, stop:
the hypothesis is testable in principle, but the current capture method cannot
decide it. Do not run the incident workload, add speculative native
instrumentation, or weaken the oracle.

### Preflight result, 2026-09-14

The mandatory gate failed on GraalVM 25.0.4 and macOS 26.6.2, so the incident
workload did not run. One controlled authenticated JDBC connection executed 176
CHECKPOINTs and inserted 5,632 rows over 15 seconds on the real
`river-connection-0` virtual-thread path. `/usr/bin/sample 18280 12 1` exited
normally, and the in-process JFR completed.

Full-depth JFR decoding found 15 `jdk.NativeMethodSample` events with the actual
`pwrite0` bridge, `FileChannelImpl.write`, `NioDurableFile.write`, and checkpoint
frames on virtual Java thread 49. Those events report `osThreadId=0` and no OS
thread name. The macOS sample independently shows `pwrite0` on ForkJoin carrier
OS threads, but its intervening JIT frames are unresolved addresses; it does not
identify the mounted virtual thread or River caller. The two outputs therefore
cannot prove the required carrier-to-virtual-thread join.

The 1 ms pending-event sample captured seven active `POSITIONAL_WRITE` events on
Java thread 49 and no active `RESIZE_GROWTH` event. That absence does not
establish whether resize executed. The collector combination did not demonstrate
the required chain for both call sites, so the preflight is inconclusive for the
call-site hypothesis and execution stops here. Raw evidence and the retained
scratch driver are under
`benchmark-results/checkpoint-20260914/operation-kind-preflight/`.

## One incident attempt

After the preflight passes, use the exact retained workload configuration once on
this Mac under the user's accepted panic risk. Build before observation; run no
other build or workload concurrently.

```sh
tools/tps-test.sh --version=checkpoint-isolated-01 --profile=tiny \
  --mix=standard --terminals=4 --scheduling=no-wait-stress \
  --evidence=diagnostic --fresh-load=true --warehouses=1 --batch-rows=32 \
  --maximum-attempts=32 --warmup-seconds=2 --measured-seconds=60 \
  --seed=42 --isolation=serializable --sample-id=checkpoint-isolated-01 \
  --server-java-option=-Driver.diagnostics.pendingFileWrites=true \
  --server-jfr="$incident_dir/server.jfr" \
  --output-dir="$incident_dir/run" > "$incident_dir/console.log" 2>&1
```

Use the existing state trigger and collectors from the capture plan: observe
CHECKPOINT or an active pending write, then capture distinct timestamped process,
Java thread, `lsof`, `/usr/bin/sample`, and JFR snapshots while the operation is
still pending. Start the already specified administrator `fs_usage` command
before Java only if its controlled preflight produced records; it remains
supporting timing evidence, not the writer-to-kernel join. Do not wait behind a
failed `jcmd` before taking native evidence.

## Decision oracle

- **Confirm:** during the incident, one same-thread mixed stack contains the
  mounted River resize call and the actual blocked native/kernel write chain, and
  its pending event is marked `RESIZE_GROWTH`. Report only that
  `NioDurableFile.resize` initiated the identified stalled write.
- **Falsify:** the equivalent same-thread incident evidence contains the ordinary
  River write call and is marked `POSITIONAL_WRITE`. Report only that resize is
  false for this incident; the ordinary write may still have extended EOF.
- **Inconclusive:** no recurrence; no active sample; only an uncaptured overlap;
  capture before native entry; failed attach/tracing; only completed filesystem
  calls; ambiguous or changing carrier association; multiple unmatched writes;
  missing operation kind; or evidence lost/corrupt after a host panic.

Persist raw console/runner output, server logs, JFR and decoded pending events,
Java/native snapshots, filesystem trace when available, and any panic report in
the pre-created persistent incident directory, then copy it to separate persistent
storage. Record source revision and OS/JDK context as ordinary notes. JFR buffers or
temporary files may not survive a panic; their absence makes the result
inconclusive rather than negative evidence. Do not add source fingerprints,
runtime descriptors, host leases, terminal receipts, or evidence-validity gates.

Stop after this one attempt on any confirm, falsify, or inconclusive result. Do
not retry automatically, sweep workloads, change providers, implement a fix, or
broaden capture tooling. Recurrence is not guaranteed; the explicit conditional
oracle is what makes this single hypothesis rigorous.
