# Frontend Showcase Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the authenticated finance showcase to a responsive, light enterprise-fintech visual system without altering its financial or API behavior.

**Architecture:** Centralize visual tokens and reusable display classes in `index.css`, replace page-local visual duplication with small presentational components, and preserve each page's existing requests and event handlers. ECharts options receive theme-only styling and keep their current series and source values.

**Tech Stack:** React 19, TypeScript, Vite, CSS, ECharts 6.

## Global Constraints

- Modify frontend and necessary frontend documentation only; do not alter `backend/`, `sites-demo`, `AGENTS.md`, DTOs, or API behavior.
- Add no UI framework, chart library, state library, fake market data, growth values, or frontend financial aggregation.
- Use inline SVG for new visual icons and retain existing interactive behavior.
- Create exactly two local commits and do not push.

---

### Task 1: Establish shared fintech visual system and dashboard

**Files:**
- Modify: `frontend/src/index.css`, `frontend/src/components/Layout.tsx`, `frontend/src/components/PageHeader.tsx`, `frontend/src/components/Pagination.tsx`, `frontend/src/components/Feedback.tsx`, `frontend/src/components/charts/*.tsx`, `frontend/src/pages/Dashboard.tsx`
- Create: `frontend/src/components/Visual.tsx`
- Create: `docs/superpowers/specs/2026-07-20-fintech-showcase-polish-design.md`
- Create: `docs/superpowers/plans/2026-07-20-fintech-showcase-polish.md`

- [ ] Add named CSS tokens, shared layout/card/table/form/button/badge rules, focus states, and desktop/tablet/mobile media queries.
- [ ] Convert layout navigation to semantic active links while preserving the four existing routes and logout action.
- [ ] Provide presentational inline-SVG icon, summary-card, badge, amount, and page-header helpers that accept supplied text and numbers only.
- [ ] Apply consistent ECharts grid, axis, tooltip, legend, and semantic series colors without changing series values.
- [ ] Recompose Dashboard from the existing `/dashboard` fields into five KPIs, the existing three charts, and the existing recent-transactions table; remove only duplicate route tiles.
- [ ] Run `npm test -- --run`, `npm run lint`, `npm run build`, and `git diff --check`; commit the scoped result as `style: establish fintech visual system`.

### Task 2: Polish management pages and verify responsive behavior

**Files:**
- Modify: `frontend/src/pages/Accounts.tsx`, `frontend/src/pages/Assets.tsx`, `frontend/src/pages/Transactions.tsx`

- [ ] Replace local visual styles with shared page header, panel, form, table, badges, amount, and responsive wrappers while retaining all current request calls and callbacks.
- [ ] Add safe account count summaries only from the existing loaded records and supplied total; do not sum account balances.
- [ ] Show existing asset type, market quote state, profit/loss, and existing actions with semantic visual hierarchy; retain quote refresh, price update, close, delete, details, and create.
- [ ] Preserve all transaction filter fields, request parameters, edit/delete behavior, and data columns while applying type badges and semantic amount colors.
- [ ] Verify the four routes at 1440px, 1024px, 768px, and 390px; run `npm test -- --run`, `npm run lint`, `npm run build`, `git diff --check`, and the prescribed git scope checks; commit as `style: polish finance management pages`.
