<!--
Sync Impact Report
- Version change: none -> 1.0.0
- Modified principles: (added) Player-First, Secure Authentication, Data Privacy & Minimization, Test-First (NON-NEGOTIABLE), Interoperability & Exportability, Observability & Logging, Simplicity & Incremental Delivery
- Added sections: Additional Constraints, Development Workflow
- Removed sections: none
- Templates requiring updates: .specify/templates/plan-template.md ✅ reviewed, .specify/templates/spec-template.md ✅ reviewed, .specify/templates/tasks-template.md ✅ reviewed
- Follow-up TODOs: none
-->

# MyHockeyStats Constitution

## Core Principles

### Player-First (NON-NEGOTIABLE)
All product decisions and technical trade-offs MUST prioritize the youth player and parent user journeys. Features MUST be delivered incrementally such that each deployed increment provides a complete, testable slice of value to players or parents (e.g., account creation, profile editing, season view, game history export).

Rationale: the project exists to serve youth players and parents; keeping the player journey central prevents scope creep and ensures usable increments.

### Secure Authentication & Access Control
Authentication and authorization are MANDATORY. Accounts for players and parents MUST be unique and protected by industry-standard mechanisms (password hashing, secure session handling). Parent accounts that view a child's data MUST use explicit linking or consent; access delegation MUST be auditable.

Rationale: personal and performance data are sensitive and require clear, enforceable access controls.

### Data Privacy & Minimization
Collect only data essential to the product (profile fields, game history, minimal PII). The system MUST store birthdate and location only as needed for federation lookups and identification, and retain data according to documented retention policies. Any external queries to leagues (THF/AHF/AYHL) MUST be performed with the minimal identifying information required and logged.

Rationale: reduce risk and regulatory exposure while enabling necessary features like season/team lookup.

### Test-First (NON-NEGOTIABLE)
All new features MUST include tests before implementation: unit tests for core logic, contract tests for public APIs, and integration tests for cross-component flows. Tests MUST be runnable locally and in CI and MUST fail until the feature is implemented.

Rationale: ensures reliability and incremental safety as the codebase grows.

### Interoperability & Exportability
The product MUST support exporting user-facing reports (PDF export of season and game history) and must design APIs and data contracts to permit integration with external sources (e.g., THF/AHF/AYHL). External integrations MUST be encapsulated behind services with clear retry/backoff and error handling.

Rationale: players and parents need portable records and reliable external data augmentation.

### Observability & Logging
Structured logging, error reporting, and basic telemetry for key flows (sign-up, login, season lookup, export) MUST be implemented. Logs MUST avoid storing raw sensitive fields (e.g., full password, full PII) and MUST include correlation IDs for traceability.

Rationale: operational visibility is required to diagnose problems and verify correct behavior.

### Simplicity & Incremental Delivery
Prefer simple, well-tested implementations over speculative complexity. When faced with multiple approaches, choose the option that delivers the required user value earliest. Architectural expansions MUST be justified with a measurable need and added incrementally.

Rationale: keeps the project deliverable and maintainable.

## Additional Constraints

- Technology choices SHOULD favor web-first implementations compatible with typical host platforms; specifics (language, framework) are decided per-spec and captured in feature plans.
- Security: store credentials and secrets securely; apply TLS for external calls; follow secure defaults in production configurations.
- Performance: set realistic, testable SLAs per-feature in the implementation plan.
- Export Files: PDF exports MUST be generated server-side or via a deterministic, audited renderer and MUST include only data the user is authorized to view.

## Development Workflow

- Branching: feature branches for each spec; PRs MUST include a link to the feature spec and reference the relevant constitution principles when deviating from defaults.
- Reviews: code changes MUST be peer-reviewed; security- or data-sensitive changes MUST include at least one reviewer with privacy/security context.
- CI: run tests and linting on every PR. Gatekeepers defined in `plan.md` and `spec.md` MUST be satisfied before merging.
- Documentation: each feature MUST include quickstart and user-facing docs for exported artifacts (PDF schemas, data fields used for external lookups).

## Governance

- Amendments: any change to this constitution MUST be proposed as a PR that (a) states the rationale, (b) lists affected principles and templates, and (c) includes a migration or compliance plan when required. A MINOR version increment is required for additions or material expansions; a MAJOR increment is required for removals or redefinitions that break prior governance.

- Approval: amendments require review and approval from the core maintainers. For changes affecting security, privacy, or data retention, at least two maintainers MUST approve.

- Versioning policy:
  - MAJOR: incompatible governance changes (removing or fundamentally redefining principles).
  - MINOR: adding a principle or materially expanding existing guidance.
  - PATCH: wording clarifications, typos, and non-semantic refinements.

- Compliance reviews: feature plans and specs MUST include a short "Constitution Check" section that lists any gates or principle deviations and an explicit justification for each.

**Version**: 1.0.0 | **Ratified**: 2026-02-23 | **Last Amended**: 2026-02-23

```
