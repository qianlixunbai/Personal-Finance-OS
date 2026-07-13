# Sites Demo

`sites-demo` is a portfolio-oriented static demo branch for Personal Finance OS v1.2. It is intentionally separate from `zh-cn` and does not change backend behavior or the frozen architecture.

## Run locally

```powershell
cd frontend
npm install
npm run dev
```

Open the displayed local address. The entry page explains the project and the **进入静态演示** button opens the Dashboard.

No PostgreSQL, Java, Spring Boot, account, or environment variable is required.

## Demo behavior

- The Axios instance uses a branch-local `src/demo/adapter.ts` adapter rather than a network adapter.
- Sample data is centralized in `src/demo/data.ts` and covers Dashboard, Accounts, Assets, Transactions, categories, six-month trends, positive/negative balances, and a zero-activity month.
- Page mutations are in-memory demonstration interactions only. Refreshing the page restores the initial sample data.
- Every authenticated page has a visible notice that the data is fictional and not a real account.

## Static hosting

- Vite builds with a relative base path (`./`) so static assets work under a project subdirectory.
- The frontend uses `HashRouter`; deployed routes are addressable as `#/`, `#/accounts`, `#/assets`, and `#/transactions` without a host-side history fallback.

Build the deployable artifact with:

```powershell
cd frontend
npm run build
```

Publish the generated `frontend/dist` directory to any static host. This branch intentionally contains no deployment workflow or CI configuration.
