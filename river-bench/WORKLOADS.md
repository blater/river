# River generated workload schemas

<!-- markdownlint-disable MD013 -->

Status: schema v2 developer harness; not a P05 baseline or production format

These are River-owned synthetic schemas. They do not copy, adapt, download, or
claim to reproduce either external dataset discussed in the performance plan.
The manifest records the generator version, seed, complete scale configuration,
data row count, byte count, schema ID, and SHA-256 of every table. A generator or
semantic change requires a new version and baseline. `record_count` excludes the
single TSV header row; `byte_count` includes it.

All files are UTF-8 TSV with LF record endings. Empty fields are SQL-null inputs.
Generated text contains no tab, CR, or LF. Integer ranges below are inclusive.

## RiverBank v2

`riverbank.accounts.v2` has this fixed column order:

| Column | Type and constraint |
| --- | --- |
| `account_id` | Positive 64-bit integer; unique primary key |
| `branch_id` | Positive integer in the configured branch range |
| `customer_id` | Positive 64-bit integer; two accounts per generated customer key |
| `opened_at_epoch_ms` | Valid instant in 2020-2024, UTC epoch milliseconds |
| `status` | `active` or `frozen` |
| `balance_minor` | Non-negative minor currency units; initial workload balance |
| `risk_band` | Integer 0-4 |

`riverbank.transactions.v2` has this fixed column order:

| Column | Type and constraint |
| --- | --- |
| `transaction_id` | Positive monotonic 64-bit integer; unique primary key |
| `occurred_at_epoch_ms` | Valid instant in 2020-2024, UTC epoch milliseconds |
| `type` | `transfer`, `deposit`, `withdrawal`, `card_authorize`, or `card_reverse` |
| `from_account_id` | Nullable account foreign key; present except for deposits |
| `to_account_id` | Nullable account foreign key; present for transfers and deposits |
| `amount_minor` | Positive integer in 1-250000 minor currency units |
| `idempotency_key` | Unique ASCII identifier derived from version, seed, and sequence |

A transfer has two different accounts. The tables are load data plus a
deterministic operation stream: `balance_minor` is the starting balance and the
driver must apply funds checks, constraint failures, commit outcomes, and
retries. The generator does not invent successful final balances before those
transaction semantics exist.

Each account choice first selects the declared hot lane with probability 80/100
or the all-account lane with probability 20/100. The latter can also select a
hot account. This provides deterministic contention and independent cold keys
without an unbounded popularity table.

## RiverPapers v2

`riverpapers.authors.v2` has `author_id` (positive unique key), `display_name`
(River-owned UTF-8 text), and `institution_id` (positive configured key).

`riverpapers.documents.v2` has this fixed column order:

| Column | Type and constraint |
| --- | --- |
| `document_id` | Positive monotonic 64-bit integer; unique primary key |
| `doi` | Unique synthetic ASCII DOI |
| `title` | Non-empty River-owned UTF-8 text with a controlled common prefix |
| `institution_id` | Positive integer in the configured institution range |
| `published_epoch_day` | Valid day in 2020-2024, UTC epoch-day encoding |
| `version` | Integer 1-4 |
| `category` | One of six stable category values with a declared hot category |
| `publication_doi` | Nullable unique-by-document synthetic DOI |
| `abstract_utf8` | River-owned text within configured inclusive token bounds |

`riverpapers.document_authors.v2` has `document_id`, `author_id`, and
`author_ordinal`. Every document has ordinals 1-3 and three distinct valid
author keys. The logical primary key is `(document_id, author_ordinal)`; both ID
columns are foreign keys to their generated tables.

Abstract token choices use declared 70/20/10 common/category/non-ASCII lanes.
Titles, DOI values, categories, dates, and text widths support future unique,
category/date, nullable, prefix, join, scan, sort, spill, and indexing workloads.
The token stream is data for scans and future supported operators, not a claim
of full-text-index support.

## Determinism and resource bounds

Every value is a pure function of the generator version, signed 64-bit seed,
row sequence, and a stable value lane. Output is independent of table emission
order and scratch-buffer size. Generation accepts one caller-owned 64 byte to
1 MiB scratch array, emits bounded chunks, manually encodes numbers, and reuses
static token bytes. It retains no row collection or cardinality-sized state.

The public scale records reject zero, inconsistent, and out-of-policy counts.
RiverBank currently caps branches at 100000, accounts at 10000000, and
transactions at 2000000000. RiverPapers caps documents at 100000000, authors at
10000000, institutions at 1000000, and abstract width at 4096 tokens. Derived
relation cardinality uses checked arithmetic before generation.

Artifact publication uses two deterministic passes. The first writes to a
digest-only sink. The second writes to owned staging. A change in status, rows,
bytes, or checksum between passes aborts publication and cleans owned staging.
Persisted files are then streamed through SHA-256 verification before the
existing create-once atomic directory install.
