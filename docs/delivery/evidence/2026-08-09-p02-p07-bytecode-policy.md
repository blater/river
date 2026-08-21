# P02/P07 scoped hot-path bytecode evidence: 2026-08-09

Status: local executable mechanism evidence; not P02, P07, G0, or M0
promotion

## Contract

`verifyHotPathBytecode` reads Java 25 production `.class` files with the Java 25
`java.lang.classfile` API. It requires the exact Java 25 class version, forces
structured traversal of classes, methods, code, instructions, switches, member
references, arrays, and bootstrap data, and runs the platform verifier. The
verifier uses a controlled hierarchy assembled from every parsed River class,
the declared production runtime class paths, and JDK platform resources. It
does not consult the ambient `buildSrc` class loader. Unresolved hierarchy and
all other verification diagnostics fail the task. The gate does not scan raw
class bytes or depend on human-formatted disassembly.

The contract selects a small set of exact binary class names, method names, and
JVM descriptors. A missing class or changed descriptor fails the task rather
than silently reducing coverage.

The initial selection is the existing bounded observability data plane:

- ring enable, publish, poll, saturation, and consumer-ownership paths;
- level-gated enable and publish paths;
- caller-owned diagnostic event and context reset/copy/set paths; and
- severity comparison used by those paths.

Constructors, ring factories, exporters, redaction, and other control or
boundary paths are deliberately outside this zero-steady-state-allocation
contract. The existing source policy continues to reserve the named WAL,
buffer, storage, transaction-commit, and vector package families. Their exact
production methods must be added to this bytecode manifest when those hot paths
are implemented.

The default contract denies:

- object allocation;
- primitive-wrapper `valueOf` calls and wrapper constructors;
- stream and collector calls;
- `String.format`, `String.formatted`, `Formatter`, and print formatting calls;
- exception construction and `athrow`;
- invokedynamic string concatenation and other invokedynamic call sites; and
- every compiler-emitted primitive, reference, or multidimensional array,
  including the array created at an ordinary varargs call site.

Rule allowlists bind an exact method, rule, instruction detail, and single
occurrence. An additional matching occurrence or stale unused allowance fails
the build. The only initial allowlist is `BoundedEventRing.onSaturation`: javac
emits an unreachable
`MatchException` allocation/throw fallback for the exhaustive enum switch.
The normal declared enum cases do not enter that fallback. Keeping this
compiler artifact visible and narrowly allowlisted is preferable to weakening
exception checks for every hot method.

## Executable proof

`verifyHotPathBytecodeFixtures` compiles Java 25 fixtures and proves rejection
of every rule. Separate fixtures cover wrapper `valueOf`, a wrapper constructor,
stream use, collector use, `String.format`, `Formatter`, exception construction,
exception throw, object allocation, array allocation, string concatenation, a
captured lambda, and the array allocation at a varargs call. Positive fixtures
prove a primitive status return and caller-owned array copy/publish paths pass.

Adversarial fixtures prove rejection of an unsupported version, malformed
constant-pool tag, truncation, reserved opcode, invalid `wide`, non-zero
`invokeinterface` and `invokedynamic` reserved operands, invalid `newarray`
type, zero `multianewarray` dimensions, invalid table-switch bounds and target,
unsorted lookup-switch keys, inconsistent and duplicate `Code` attributes, and
an invalid bootstrap reference. A misleading invokedynamic call-site name
proves classification comes from `StringConcatFactory` or
`LambdaMetafactory`, not the call-site name.

Additional regressions prove that `ThreadDeath` is a throwable despite its
name, a non-throwable class ending in `Exception` is not, a custom integer
method named `stream` is not a stream API, and an object-allocation allowance
cannot mask throwable construction. Separate fixtures prove that external
throwable ancestry is resolved only through an explicit hierarchy path,
unknown ancestry fails closed, hierarchy resolution cannot suppress a later
semantic verifier error, and one allowance consumes only one of two identical
allocation occurrences. The fixture compiler explicitly clears its scoped
output and proves a seeded stale marker cannot survive.

Both the production audit and fixture proof are dependencies of local
`check`. They add no external dependency.

Local validation completed with:

- `GRADLE_USER_HOME=/private/tmp/river-gradle-home ./gradlew
  verifySourcePolicy verifyHotPathBytecodeFixtures verifyHotPathBytecode
  --rerun-tasks --stacktrace`, with 69 executed tasks; and
- `RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify --rerun-tasks`,
  including both exact archive builds, byte-for-byte comparison, and the final
  clean 149-task `check` run.

After the controlled-hierarchy fixes received an independent `SAFE` verdict,
the reviewed sequence was integrated through `a8ea3fb`. The root branch reran
the 69-task focused audit and the complete gate: both clean 58-archive builds
matched byte for byte, the final 149 tasks executed, and 218 JUnit tests passed
with zero failures, errors, or skips.

## Proof boundary

This audit proves that the selected compiled method bodies contain none of the
denied bytecode patterns except exact reviewed allowances. It cannot prove:

- whether the JIT removes a bytecode allocation through escape analysis;
- whether a called library method, native adapter, reflection, or method handle
  allocates, copies, blocks, or throws;
- allocation caused by JVM runtime services, class initialization, safepoints,
  or instrumentation;
- whether an array allocation originated from source-level varargs rather than
  an ordinary explicit array expression; or
- numeric steady-state allocation, copy, throughput, contention, or tail
  latency on the accepted P05 runner.

The audit resolves invokedynamic bootstrap owners and rejects string
concatenation separately from lambdas and other dynamic call sites. It does not
claim to identify source-level varargs; the ordinary array-allocation rule
rejects the generated array without heuristic look-ahead. Runtime allocation
counters, JMH/JFR profiles, copy counters, and representative sustained
workloads remain the performance source of truth.

## Promotion decision

P02 and P07 remain `active`. This closes their scoped local bytecode-mechanism
gap only. P02 still needs combined clean-checkout evidence and promotion review.
P07 still needs the already-consumed local WAL generation migrated from a raw
primitive to its semantic type, canonical numeric budgets on the P05 runner,
upstream P02/P03 promotion, and final promotion review. Future concrete
kernel-method coverage belongs to the gate that introduces each hot path.
