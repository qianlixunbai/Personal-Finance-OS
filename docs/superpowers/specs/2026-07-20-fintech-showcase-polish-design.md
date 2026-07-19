# Frontend Showcase Polish Design

## Scope

Refresh the four authenticated React pages (Dashboard, Accounts, Assets, and
Transactions) into a light enterprise-fintech presentation without changing
routes, API contracts, backend behavior, or financial calculations.

## Visual System

`frontend/src/index.css` becomes the source of the shared CSS tokens: page and
card surfaces, primary blue, semantic positive/negative/neutral colors,
typography, radii, spacing, shadows, focus rings, breakpoints, and transitions.
The layout uses a centered wide content shell, a dark navy navigation bar, and
responsive grids. Inline SVG icons are limited to visual labels and retain text
or accessible names for meaning.

## Components and Data Boundaries

Existing `PageHeader`, `Pagination`, chart cards, alerts, tables, and forms are
restyled and receive only presentational props/classes. Small shared display
components may represent icons, badges, summary cards, and amount text; they
do not calculate financial values. Dashboard keeps `/dashboard` values as-is.
Account overview cards use only the loaded account page and its supplied total;
asset and transaction pages show only fields already supplied by their APIs.

## Page Behavior

- Dashboard removes duplicate navigation tiles, keeps five supplied KPIs, three
  supplied charts, and recent transactions.
- Accounts keeps create, edit, deactivate, paging, and existing account fields.
- Assets keeps create, details, price update, close, delete, and quote refresh.
- Transactions keeps filters, create/edit/delete, paging, and all request
  parameters unchanged.

## Accessibility and Responsive Behavior

Controls have visible keyboard focus, tables remain semantic and scroll safely
on narrow screens, and card/table grids collapse at tablet and mobile widths.
Color supplements rather than replaces text labels and numeric signs.
