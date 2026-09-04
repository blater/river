# `tic-1dda` stable-master P0 revalidation

Status: **blocked; not a P0 promotion result**

Evidence class: River-specific diagnostic correctness and scaling evidence.
This is not an audited TPC-C result, an Alpha3 result, a cross-database
comparison, or an accepted performance checkpoint.

## Decision

The 40-run serializable campaign produced strong current-source correctness
evidence but fails the P0 gate. No implementation change was made.

All 40 runs completed through checkpoint with zero measured or drain retries,
failures, retry exhaustion, unknown outcomes, timeouts, cancellations,
diagnostic overflows, or terminal lock/transaction/waiter residue. Requested
and effective JDBC/program isolation was `SERIALIZABLE` in every run. The
historical measured deadlock fingerprints and New Order `warehouse-read`
failure were absent.

Promotion is nevertheless blocked for four independent reasons:

1. The standard-mix 10:2-terminal geometric TPS ratio is 0.7122 with an
   individual two-sided 95% Student-t interval of [0.5283, 0.9602]. The interval
   is wholly below 1.0, so the predeclared performance guard detects a scaling
   regression. Zero retries do not waive it.
2. The server reports 41,346 actual successful lock blocks, but it does not
   classify each by resource scope, requested/held mode, queue relationship,
   and enforced grant predicate. Deadlock exemplars classify only victim
   cycles. Literal failure-mode displacement and post-block liveness therefore
   cannot be proved.
3. There is no current-source correlated mixed-isolation reproducer, and the
   terminal metrics expose no retained-snapshot gauge. Zero retained snapshots
   is only indirectly suggested by zero active transactions/locks/waiters.
4. The build/run evidence lacks a retained exact-source build log and hashed
   classpath manifest, and host exclusion did not prove Gradle daemon idleness.
   One retained discriminator sample overlapped the start of an unrelated
   Gradle build.

No `perf-checkpoint-*` tag is proposed or accepted, no clean full gate was run,
and `tic-1dda` remains `in_progress`. After the two disjoint prerequisite
contracts below close, the full P0 bundle must be rerun, including the
mixed-isolation reproducer and clean full gate. The integration owner, not this
evidence author, owns any eventual no-fast-forward merge and annotated tag.

## Source and timing provenance

- Measured clean source and pushed ticket claim:
  `39a3dffe6644c4ada010121e0aacb08977526b5e` on
  `origin/ticket/tic-1dda-p0-revalidation`.
- Production/tool base:
  `10acfa58664f715c0023b31280d75eabdcbfa5cd`.
- All 40 metadata records report that commit, `git.dirty_state=clean`, stable
  start/finish workspace fingerprint
  `845e4941cdc17c2eb71eb2eb7dc3058cbd84315ab5be7a60c4371ce36e036510`,
  Java 25.0.4, and empty stderr.
- There was no source tag. Since the gate failed, this is not retrospectively
  converted into a tagged performance checkpoint.
- The campaign plan was created at 20:53:11 BST. `origin/master` had advanced
  from the branch base to `d4d6cd841b3dc3064c3f03cbc7195b8f11b4cfc7`
  at approximately 20:53:04 BST; its commit timestamp is 20:53:00 BST. By
  analysis time the remote tip was
  `7cf93b47e74ba62a9915a8aea3f98e46b1f2d641`. The complete
  `10acfa5..7cf93b4` delta is README/ticket/plan evidence only and changes no
  production, test, build, or `tools/tps-test.sh` input. Therefore `10acfa5`
  is the exact production/tool base, but it was not the current remote tip when
  the plan was declared.
- The original plan SHA-256 before its post-campaign addendum was
  `625eee17380055f71771ccad7e0137c655e90175266f059c747fd622011bc4d9`.
  The additive final plan is
  `/private/tmp/river-tic-1dda-p0-evidence-20260904/campaign-plan.md`, SHA-256
  `b1318022c155180bea8d6336fa8369773088f3bdb7064bc0744ad72e92c90c53`.
- `b1-d2` completed at 20:55:22 BST. The resumable runner was created at
  20:59:01 BST, SHA-256
  `398c5a37ce407dd6f2e8c065e1ab71c7729b0724d3a82c7b8c9ddd6a200bec39`.
  The plan and `b1-d2` metadata preserve the same intended command, but the
  runner governed only the remaining 39 invocations.
- `b5-d4` ran from 21:10:57 through 21:11:15 BST. An unrelated Gradle daemon
  began at 21:11:06, and an unrelated `TpccServerMain`/`TpccAcceptanceMain`
  pair was visible at the next exclusion check. `b5-d4` is retained and used by
  the predeclared primary calculation, but is potentially contaminated
  performance evidence. It does not affect the decisive standard-mix 10:2
  result.

## Commands and fixed configuration

The first sandboxed build attempt failed before compilation because Gradle
could not create its local file-lock coordination socket. The authorized local
rerun completed successfully in six seconds with 18 tasks (two executed and 16
from cache):

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-1dda \
  ./gradlew \
  --project-cache-dir /private/tmp/river-project-cache-tic-1dda \
  :river-bench:classes
```

That console output was not retained as a hashed artifact, which is one of the
provenance blockers. All samples then used the precompiled classes with
`RIVER_TPS_SKIP_BUILD=true`. The exact command shape was:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-tic-1dda \
RIVER_TPS_SKIP_BUILD=true \
tools/tps-test.sh \
  --profile=tiny \
  --mix=<new-order-payment-50-50|standard> \
  --scheduling=no-wait-stress \
  --evidence=diagnostic \
  --fresh-load=true \
  --warehouses=1 \
  --terminals=<2|3|4|10> \
  --batch-rows=32 \
  --maximum-attempts=32 \
  --warmup-seconds=1 \
  --measured-seconds=10 \
  --seed=42 \
  --isolation=serializable \
  --deadlock-diagnostics-bytes=8388608 \
  --deadlock-diagnostics-epochs=4 \
  --deadlock-diagnostics-signatures-per-epoch=64 \
  --deadlock-diagnostics-events-per-epoch=16384 \
  --deadlock-diagnostics-exemplars-per-signature=1 \
  --deadlock-diagnostics-maximum-cycle-edges=16 \
  --sample-id=<block-cell> \
  --output-dir=/private/tmp/river-tic-1dda-p0-evidence-20260904/<block-cell>
```

Five drift-balanced blocks were run. Odd blocks used terminal order 2, 3, 4,
10 and discriminator before standard; even blocks reversed terminals and mix
order. No harness, JFR, profile, `clean`, or implementation change was used.
The integration owner directed that `verify-clean-checkout` not run after the
blocked disposition; it remains mandatory for an eventual passing checkpoint.

## Artifact manifest

Every directory below is rooted at
`/private/tmp/river-tic-1dda-p0-evidence-20260904`. Each contains the acceptance
artifact, metadata, client stdout/stderr/combined output, server log, and server
metrics. The metadata hashes all six files. Recalculation found zero file-hash
or database-digest mismatches, 40 unique run IDs, eight configuration
fingerprints, 40 schema-v2 artifacts, and no non-empty stderr file.

| Sample | Mix | Terminals | Commits | Attempts | Retries | TPS | Run ID | Artifact SHA-256 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `b1-d2` | `new-order-payment-50-50` | 2 | 539 | 541 | 0 | 53.9 | `80120421-38b2-4028-970e-691b2a04f491` | `aa30e0601846b007d48d9ac6c1d0de466b2f9cff4fa416883e8dfa4b4bad08f3` |
| `b1-s2` | `standard` | 2 | 1,240 | 1,250 | 0 | 124.0 | `3dc10b4f-4df0-4092-8f45-941b4efe78c1` | `d9623b6d71968f05a6a8c8ff4ff16aa196ebd23411914a3275ef1185a449feb6` |
| `b1-d3` | `new-order-payment-50-50` | 3 | 1,249 | 1,255 | 0 | 124.9 | `026c7a77-1472-4dbf-86db-4c8f265735eb` | `fe38e76a4abcd3b0e4fb7a7a41850862afb9118082b6b9bbaf920149fc102cd2` |
| `b1-s3` | `standard` | 3 | 748 | 761 | 0 | 74.8 | `7d1107e9-c7ca-487e-94cc-5d115ae31303` | `a263ed02423cd84130572c48ac88c0c215db0b432dbef2537b93a61c74c27961` |
| `b1-d4` | `new-order-payment-50-50` | 4 | 973 | 977 | 0 | 97.3 | `74080a16-8243-4a40-a65a-af1c17993625` | `cf380ce4c6e32d66b02aa59fd199335efc9dbe4b85b5fa452fe1f3e0a8b8bb05` |
| `b1-s4` | `standard` | 4 | 1,004 | 1,021 | 0 | 100.4 | `f8640448-703f-43c1-809a-6a380faeb017` | `dd81ec2562ff1f81723467b828d252551368e9b82b82af85e239279c4406b87a` |
| `b1-d10` | `new-order-payment-50-50` | 10 | 480 | 492 | 0 | 48.0 | `af722a49-3c8b-4f72-8d3c-7e3d93cd1575` | `7cbb3cae91507b872e7662a478a1640f8aadd97e720729416c9997b136e77b58` |
| `b1-s10` | `standard` | 10 | 698 | 710 | 0 | 69.8 | `13b6f870-6ba6-40fd-ab8b-0811a8015794` | `7c55d9e7a61809a4e2b8b52f2121b918a40dddbcb3f7e3386dbcdb32ea57fdbc` |
| `b2-s10` | `standard` | 10 | 481 | 493 | 0 | 48.1 | `fb50a8fe-3491-4f30-a09b-923314b10794` | `38b0d97963f7e29c781b576975b3306593af3580da70b81c74d65f954cb8b07f` |
| `b2-d10` | `new-order-payment-50-50` | 10 | 552 | 564 | 0 | 55.2 | `fabf38dc-81c3-4293-a111-d023d246f240` | `83218f3889f2e89389a8156fe564be687c09105a85b693131df1d68f38f47c0d` |
| `b2-s4` | `standard` | 4 | 584 | 589 | 0 | 58.4 | `ceeb016c-8cf8-4864-bdf4-63d523f72ce2` | `50aed83a20a230f8896bf6446444954039cddc6ee1f6b2c6f89c40a8671b73c6` |
| `b2-d4` | `new-order-payment-50-50` | 4 | 844 | 848 | 0 | 84.4 | `98e640e6-fc14-4865-8c1a-9f9257d0bde2` | `4df763cb6066e2d5dbc7d395ebbe1e91a1de809abd2917f1cea4fc98657ceb65` |
| `b2-s3` | `standard` | 3 | 149 | 153 | 0 | 14.9 | `7be1e356-3f7a-4831-83b0-6ad72214b79b` | `74c6e5dd0e75e820754a229d919945773d5032a8e0535f558622126a20013799` |
| `b2-d3` | `new-order-payment-50-50` | 3 | 806 | 809 | 0 | 80.6 | `727b1f35-bbff-450b-8c0d-ccccd3f81fbf` | `21b8707fa32657e0aba405d87281d888129d1b037201c3b3842f924816d6d5dd` |
| `b2-s2` | `standard` | 2 | 854 | 864 | 0 | 85.4 | `6c2f4f05-e944-417c-ba11-444843feb552` | `70e2663153e6670fe56a3963a87938d0df34291346cb8d139567cf9857f355e2` |
| `b2-d2` | `new-order-payment-50-50` | 2 | 1,203 | 1,211 | 0 | 120.3 | `50d568e1-b671-4f9a-b6d7-d926abc6a752` | `539e8bfa412988f5ad048c53ab4a44ce678bd7bd480ea20e1ca58f29a859422b` |
| `b3-d2` | `new-order-payment-50-50` | 2 | 1,169 | 1,177 | 0 | 116.9 | `bf60661f-2d1e-4f3f-9982-e496d57c0645` | `ed5093dd6e3e1d65b1fe6b461e05219aaa80c66f267d25f5b64788fc48cca4f0` |
| `b3-s2` | `standard` | 2 | 1,129 | 1,139 | 0 | 112.9 | `c30cb08b-d4ae-492f-90c4-05c380452482` | `2841c02d43c66c0763f9b09a776da43536a376f02b4e7332a0a05fa6ee80ff48` |
| `b3-d3` | `new-order-payment-50-50` | 3 | 1,070 | 1,076 | 0 | 107.0 | `5dc9158c-b2a9-4b80-9a68-107d92961d14` | `3189006bf2f57f67771d8d25a93783fa07ee75a0ca8c850da520dfeacf51922e` |
| `b3-s3` | `standard` | 3 | 1,111 | 1,127 | 0 | 111.1 | `7923b479-751c-4b57-8c5f-7522605edf13` | `5347b669f0c2edcf327b449cb736c12e2a86d80575759c7b75bb235bb21c894a` |
| `b3-d4` | `new-order-payment-50-50` | 4 | 1,143 | 1,149 | 0 | 114.3 | `2c905750-23cd-4e78-a5ee-2707b7288c7e` | `ea275bdd1a47f6530216a733419887873510aaee2745e693ba1c438a4a203d39` |
| `b3-s4` | `standard` | 4 | 971 | 988 | 0 | 97.1 | `13a355b2-2be5-49cd-a51f-1334f4cb652e` | `d5a70435b4e7813d132fdc020bd1ded66f098d7acc05acd4c00d2c1d3fd964c0` |
| `b3-d10` | `new-order-payment-50-50` | 10 | 1,041 | 1,051 | 0 | 104.1 | `abdabf8f-cd98-43ba-9fb3-43cbd5b9ca51` | `def624ea4806f42ba0340aa2736ac3bd463d3979ed2c14e6702c3716934f5ebb` |
| `b3-s10` | `standard` | 10 | 788 | 800 | 0 | 78.8 | `3d50fb17-698b-4807-aff9-bbe3eed76594` | `3d281d6529150ed28ff23d51454e2c52eee862d2d0249bd1fdca0a29eeef7f66` |
| `b4-s10` | `standard` | 10 | 971 | 983 | 0 | 97.1 | `e15748c5-9d4a-4743-b3e1-26540b2e5c1c` | `aecda8f47bffcd57756e413098286ca38cee7bfcf78ca914b7442cf70038f01d` |
| `b4-d10` | `new-order-payment-50-50` | 10 | 1,100 | 1,110 | 0 | 110.0 | `6665412e-98e5-4a29-bbc2-377d15e2280f` | `876335777f2035b61e7784184badfd20e9afdcadcd4013ab551b6736eb729ba0` |
| `b4-s4` | `standard` | 4 | 1,128 | 1,145 | 0 | 112.8 | `775a5e91-6037-4a5b-8617-e964a1b37a46` | `d60e03c2c44593fbbe93b5dac78498d72fe7562802ff5540d05987cb1c79faa7` |
| `b4-d4` | `new-order-payment-50-50` | 4 | 1,134 | 1,140 | 0 | 113.4 | `b53c2077-7461-4ad5-9888-e06ba96f39ba` | `e8961762fdea3543f6e78489805dde5755c0dfac19328daf7918e28a4667e94a` |
| `b4-s3` | `standard` | 3 | 1,078 | 1,094 | 0 | 107.8 | `fe694fac-c90a-4023-89d1-217c7a6758d2` | `7fefd36765ac32f8a6b537d13e387d9efd83568baf8039bb3cad21b3c09ee9e9` |
| `b4-d3` | `new-order-payment-50-50` | 3 | 869 | 874 | 0 | 86.9 | `ee412251-b980-456c-b25c-37bed8f72441` | `9df9f044887d45617be1479c1247ba8e1c58c5fd44d88b109c931796c993ca16` |
| `b4-s2` | `standard` | 2 | 1,083 | 1,093 | 0 | 108.3 | `cc0eae84-a7aa-4646-83a1-d71aa7e64ecd` | `c8a51cf255ea2eb5b5ce01253535d5d0c7588b2cb9f02b977efeeb6fef500f09` |
| `b4-d2` | `new-order-payment-50-50` | 2 | 1,147 | 1,155 | 0 | 114.7 | `72a5031b-4bde-4e15-a644-020fc51abace` | `9128209936810b2f0416c8b0d13212262ff4d7261ff9c943b98c1c5819ccba6b` |
| `b5-d2` | `new-order-payment-50-50` | 2 | 1,109 | 1,117 | 0 | 110.9 | `ea4c9e45-3f4f-4114-88aa-2c147e3d7509` | `9ab3a615993b792910a2fd75e0dc6470932e9c10a970082e7dc9e30d315a15a3` |
| `b5-s2` | `standard` | 2 | 1,142 | 1,152 | 0 | 114.2 | `8dd23ac4-93da-4738-977b-14bbd73084b0` | `8e3bbe908e453dd49f319fddab0c66ec1a19008a45716665d467945474b22d5c` |
| `b5-d3` | `new-order-payment-50-50` | 3 | 1,169 | 1,175 | 0 | 116.9 | `812cee97-6485-4783-af3a-c14f15e08de9` | `77f52f23de5a454cee3a09fbaf6163936434bb6c2aaa4f86617f4f3be70f16b5` |
| `b5-s3` | `standard` | 3 | 1,156 | 1,172 | 0 | 115.6 | `a1dcec92-2e90-439a-9aab-e0ce98ad4180` | `2fafc10978c7bb9b241b6e3a64b020728268772fce6164a3e54ceb83b5416d3a` |
| `b5-d4` | `new-order-payment-50-50` | 4 | 989 | 993 | 0 | 98.9 | `4fde38e2-235e-414f-ae85-359b4fbe7b87` | `cb7cc37253955079fe1c2e785b2cb14f716b3304691189bfcfe6bdf1648a9ff1` |
| `b5-s4` | `standard` | 4 | 881 | 894 | 0 | 88.1 | `975c37fb-b528-4fcd-b087-486864bff5ff` | `0128155c8b7f912e0e29ec99ced30a8c603034dfe5f35421c758a908dbb23323` |
| `b5-d10` | `new-order-payment-50-50` | 10 | 1,043 | 1,053 | 0 | 104.3 | `177eda7f-eacb-4db6-ac33-6d1dbbedc379` | `6b333532c38f0bf2181ed45b37302717022248ea8ebfba3f3301d4e49a90d47e` |
| `b5-s10` | `standard` | 10 | 1,055 | 1,067 | 0 | 105.5 | `007ff455-c9d8-47a9-9335-20622ce24611` | `74a580fd1c2fb95553cb24f7e1e0d33887bb159e463b553915b493556809788a` |

## Correctness and failure-mode reconciliation

A mechanical audit of all artifacts found:

- 40/40 `completed` at `checkpoint`, `status=OK`, exit 0, with all load,
  preflight, warmup, measured, drain, checkpoint, pre-run-invariant, and
  post-run-invariant markers;
- 37,880 measured commits and 38,262 measured-window-including-drain attempts;
- zero client retries, server retryable outcomes, failed or retry-exhausted
  family outcomes, unclassified failures, measured/drain deadlocks, lock
  timeouts, cancellations, or correlation/metrics/diagnostic overflow;
- zero New Order `warehouse-read` failures and a 0% retry/commit rate in every
  cell, below the 5% guard;
- zero terminal active transactions, held locks, and queued requests;
- 40 intentional epoch-3 preflight victims = 40 victim outcomes = 40 queued
  cancellations; each released two holdings and retained one self-validating
  two-edge `d9d2174596fe16c4` active-owner exemplar. There was no other
  fingerprint in any epoch; and
- 41,346 actual measured-window blocks, all eventually granted, totaling
  1,332,512,460,151 blocked nanoseconds.

The measured target modes are therefore zero per 38,262 attempts, with no
replacement deadlock fingerprint or retry outcome. This is meaningful evidence
that the old cross-family storm and its retry multiplier are absent. It is not
literal completion of failure-mode displacement: the 41,346 successful blocks
have only aggregate count/duration and lack the required causal dimensions, and
the retained-snapshot terminal count is unavailable.

The common-isolation matrix does not substitute for the required correlated
mixed-isolation reproducer. Historical dirty/currently incomparable artifacts
do not meet this clean-source bundle. Although `tools/tps-test.sh` accepts
`--isolation=mixed-diagnostic`, root stopped further workload runs once the
independent review confirmed the more fundamental observability, provenance,
and scaling failures.

## Scaling statistics and anomalies

The predeclared primary calculation takes the natural logarithm of the paired
within-block TPS ratio, reports its geometric mean, and applies a two-sided 95%
Student-t interval with five pairs and four degrees of freedom. No
noninferiority/noise margin was declared. A ratio interval wholly below 1.0 is
a detected regression; wholly at or above 1.0 is supported non-regression;
crossing 1.0 is inconclusive. The intervals below are individual intervals and
do not claim simultaneous/global 95% coverage across the dependent endpoint
tests.

| Mix | Terminals | TPS samples by block | Median | Mean | SD | CV |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| New Order/Payment 50/50 | 2 | 53.9, 120.3, 116.9, 114.7, 110.9 | 114.7 | 103.34 | 27.85 | 26.95% |
| New Order/Payment 50/50 | 3 | 124.9, 80.6, 107.0, 86.9, 116.9 | 107.0 | 103.26 | 19.04 | 18.44% |
| New Order/Payment 50/50 | 4 | 97.3, 84.4, 114.3, 113.4, 98.9 | 98.9 | 101.66 | 12.47 | 12.27% |
| New Order/Payment 50/50 | 10 | 48.0, 55.2, 104.1, 110.0, 104.3 | 104.1 | 84.32 | 30.07 | 35.66% |
| Standard | 2 | 124.0, 85.4, 112.9, 108.3, 114.2 | 112.9 | 108.96 | 14.36 | 13.18% |
| Standard | 3 | 74.8, 14.9, 111.1, 107.8, 115.6 | 107.8 | 84.84 | 42.30 | 49.85% |
| Standard | 4 | 100.4, 58.4, 97.1, 112.8, 88.1 | 97.1 | 91.36 | 20.44 | 22.37% |
| Standard | 10 | 69.8, 48.1, 78.8, 97.1, 105.5 | 78.8 | 79.86 | 22.73 | 28.46% |

| Mix | Paired ratio | Geometric mean | Individual 95% CI | Conclusion |
| --- | --- | ---: | --- | --- |
| New Order/Payment 50/50 | 3:2 | 1.0256 | [0.5598, 1.8790] | inconclusive |
| New Order/Payment 50/50 | 4:2 | 1.0177 | [0.6600, 1.5694] | inconclusive; `b5-d4` potentially overlapped |
| New Order/Payment 50/50 | 10:2 | 0.8003 | [0.5428, 1.1799] | inconclusive |
| New Order/Payment 50/50 | 4:3 | 0.9923 | [0.7697, 1.2792] | inconclusive; `b5-d4` potentially overlapped |
| New Order/Payment 50/50 | 10:4 | 0.7863 | [0.5302, 1.1662] | inconclusive; `b5-d4` potentially overlapped |
| Standard | 3:2 | 0.6364 | [0.2491, 1.6259] | inconclusive |
| Standard | 4:2 | 0.8252 | [0.6807, 1.0004] | inconclusive |
| Standard | 10:2 | 0.7122 | [0.5283, 0.9602] | **detected regression** |
| Standard | 4:3 | 1.2967 | [0.5760, 2.9195] | inconclusive |
| Standard | 10:4 | 0.8631 | [0.6733, 1.1064] | inconclusive |

The cold first discriminator sample (53.9 TPS), standard block-2 3-terminal
sample (14.9 TPS), broad coefficients of variation, and potentially overlapped
`b5-d4` are retained anomalies. None had retries or failed invariants. They
prevent stronger conclusions for most endpoints; they do not erase the
standard 10:2 detected regression. Added standard-mix concurrency reduced the
paired TPS ratio without turning into retries, so the bottleneck is below the
retry layer and remains unexplained by the current successful-block aggregates.

## Required P0 prerequisites

No current ticket owns the missing successful-block causality contract.
`tic-4d14` is a downstream P1 holdings audit that depends on `tic-1dda`; using
it here would create a dependency cycle and mix a different concern. Root must
create these two disjoint P0 children of `tic-5db4`, then add both as
dependencies of `tic-1dda`. The Transaction Performance Kanban should put the
two prerequisites in Now and the full `tic-1dda` revalidation immediately
Next. The coordination commits belong to the new tickets, not this branch.

### Proposed prerequisite A: classify successful lock blocking for P0

- Type: `story`
- Parent: `tic-5db4`
- Delivery: `code`
- Tags: `performance`, `tpcc`, `p0`, `locks`, `observability`
- Title: **Classify successful lock blocking for P0**
- Design: Add bounded, allocation-stable, generic, phase-scoped aggregate
  classification for every actual block by resource scope, requested mode,
  held/blocker mode, ordinary/conversion/FIFO queue relationship, and the exact
  enforced scheduler grant predicate. Scheduler admission and diagnostics must
  share the canonical predicate owner. Separately expose terminal retained
  snapshot count and reconcile actual blocks, grants, timeouts, cancellations,
  victims, and every classification bucket without TPC-C types in `river-tx`.
  Detailed events remain explicitly bounded; disabled capture reads no clocks
  and allocates nothing. Use a focused two-/ten-terminal standard diagnostic to
  attribute the detected scaling loss before changing lock policy.
- Acceptance: focused active-owner, FIFO-fairness, and conversion-priority tests
  prove exact bucket selection, grant-predicate identity, overflow rejection,
  phase separation, successful handoff, cancellation/victim separation, and
  zero terminal snapshots/transactions/locks/waiters. Aggregate buckets sum
  exactly to actual blocks and dispositions. Retained 2-/10-terminal standard
  diagnostics have matching source/configuration and reconstruct the dominant
  successful-block cause; no lock optimization is admitted by aggregate TPS
  alone.

### Proposed prerequisite B: retain promotion-grade diagnostic provenance

- Type: `story`
- Parent: `tic-5db4`
- Delivery: `code`
- Tags: `performance`, `tpcc`, `p0`, `benchmark`, `provenance`
- Title: **Retain exact build and host-exclusion provenance for P0 diagnostics**
- Design: Make `tools/tps-test.sh` retain the exact incremental/full build
  command, exit status and complete log; Git source/status fingerprints before
  build and run; Gradle/JDK identity; and a deterministic SHA-256 manifest of
  every classpath entry/file actually launched. Refuse evidence when source or
  manifest changes between build, server, client, and metadata publication.
  Before every build/workload, record a broad host exclusion that distinguishes
  idle from busy Gradle daemons and detects River builds, tests, profiles,
  clients, servers, harnesses, and database workloads. Fail closed rather than
  silently accepting an overlap. Preserve failed and interrupted evidence and
  never expose secrets.
- Acceptance: focused tests cover clean/current build, stale classes, source
  mutation, classpath mutation, missing/hash-mismatched entry, failed build,
  active Gradle daemon/build, River workload/harness/profile overlap, process
  race, interrupted run, and immutable non-overwrite publication. A retained
  diagnostic independently reproduces every build/classpath/source hash and
  proves the host exclusion remained valid for its full interval.

After both prerequisites close, rerun the correlated mixed-isolation
reproducer plus all serializable 50/50 and standard cells with a reviewed
statistical non-regression rule, explain the standard 10:2 regression, and run
the non-overlapping clean full gate. Only an all-green result may update
`docs/performance-checkpoints.md` with an exact no-fast-forward integration SHA
and root-owned annotated `perf-checkpoint-*` tag.

## Self-review

Transaction-correctness lens: the captured common-isolation attempts, zero
retry multiplier, exact preflight victim cleanup, and terminal lock state are
internally consistent. The missing mixed reproducer, retained-snapshot gauge,
and successful-block causal classification prevent a complete P0 correctness
claim.

Performance/statistics lens: all predeclared samples and anomalies remain in
the primary calculation; no post-hoc outlier removal or percentage tolerance
was introduced. Every interval is identified as individual, inconclusive
intervals are not called passes, and the standard 10:2 interval is correctly
classified as a detected regression. The run overlap and build/classpath
provenance gaps prevent these data from becoming an accepted checkpoint even
apart from that regression.
