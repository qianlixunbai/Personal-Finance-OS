# AI Development Rules

## Default workflow

For a clear, low-risk task, use: **inspect → minimum change → proportionate verification → commit only when requested**. Do not create a large plan, worktree, multi-agent effort, or repeated review cycle merely because a task exists.

## Risk escalation

Financial calculation, user isolation/security, migrations, transaction boundaries, concurrency, and locking need an explicit design check and strict verification before implementation. Use regression tests or TDD where a bug or sensitive invariant needs protection. Do not claim a test, build, smoke check, or review ran unless it actually ran.

## Scope and facts

- Treat backend code and migrations as authoritative for financial truth; frontend code must not re-aggregate core financial data.
- Read code/migrations, then ADRs, then the latest Closing Review, then active docs. Do not turn future scope into implemented capability.
- Architecture is frozen and architecture changes require an ADR.
- Preserve user-owned, untracked, historical, frozen, and out-of-scope files. Do not read, edit, stage, delete, or commit a file without authorization.

## Collaboration and review

When the user has supplied a complete plan, execute it rather than redoing brainstorming. One implementation review plus one post-fix verification is normally sufficient; do not loop on non-blocking P2/P3 issues. Create a worktree or use multiple agents only when the user asks or when independent work materially improves a suitably scoped task.

Select models by task complexity and risk, not a permanent model-to-role assignment. Use the least capable configuration that can safely handle the task; reserve stronger review for financial, security, transactional, or architectural risk.
