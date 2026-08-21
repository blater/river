# P03 module-boundary promotion review

Date: 2026-08-09

Status: active after independent review found a transitive dependency bypass

## Evidence that passed

The 29 declared modules, authoritative direct dependency graph, missing and
unknown module checks, missing/forbidden edge checks, cycle detection, and
cross-module internal-package import/export fixtures passed. The P02 hard
dependency and detached clean-checkout evidence are also satisfied.

## Blocking finding

The build declares every approved project dependency as Gradle `api`, while
`verifyModuleGraph` inspects only declared/inherited direct
`ProjectDependency` objects. It does not detect source compilation against a
public type supplied by an unapproved transitive project.

The reviewer reproduced the bypass in an exact detached clone: a public type
was added to `river-storage` and imported directly from `river-sql`. The
authoritative graph permits `river-sql` to depend on `river-catalog` and
`river-base`, not storage, but catalog exposed storage through `api`.
`:river-sql:compileJava`, `verifyModuleGraph`, and `verifySourcePolicy` all
passed 11 tasks.

## Required correction

Project edges must use `implementation` by default. Any intentional `api`
exposure must be an explicit reviewed contract and its effective downstream
visibility must agree with the authoritative allowset. A real compilation
negative must prove an unapproved public transitive type is unavailable.

P03 is therefore `active`, not passed. P02 remains passed; this finding does
not promote or demote another deliverable or authorize a milestone tag.
