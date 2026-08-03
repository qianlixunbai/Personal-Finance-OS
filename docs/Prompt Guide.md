# Prompt Guide

## Give enough context, not a ritual

State the desired outcome, files or modules in scope, acceptance criteria, and any safety boundary. For small, clear work, ask for inspection, the minimum change, and focused verification. Do not require a design workshop, worktree, parallel agents, or full-suite test run unless the risk calls for it.

## High-risk prompt checklist

For financial rules, security, migrations, transactions, locks, or user isolation, provide:

- the authoritative source (migration, ADR, API contract, or Closing Review);
- exact calculation, rounding, idempotency, and rollback expectations;
- concurrency/lock order and cross-user behavior when relevant;
- tests, runtime smoke, or review evidence required for acceptance.

Ask the implementer to distinguish confirmed facts from assumptions, preserve append-only and backend-authoritative financial rules, and stop for clarification when a choice would materially expand scope.

## Documentation prompts

Specify whether a document is active, frozen, or historical. Active documentation should reflect the current HEAD. ADRs, design records, logs, and Closing Reviews are historical evidence and should not be rewritten merely to look current. Require link and stale-claim checks, but do not demand application tests for a documentation-only change unless code/configuration is touched.

## Completion report

Ask for changed files, facts checked, commands actually run and their results, known limitations, and whether any tests were intentionally not run. Never ask an agent to invent a pass result or treat a future capability as complete.
