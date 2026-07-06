# Development Log (2026-07-06): Register Validation Investigation

## Goal

Investigate and fix the “registration failed” issue in the frontend registration flow, and confirm whether a preset account or a backend failure was involved.

## Symptoms

- The registration page immediately showed “registration failed” after submit.
- It looked like the project might have a built-in preset account.

## Findings

1. The project does not have a preset login account.
2. The backend registration endpoint can return `200 success` normally.
3. The real reason for the failure was user-name validation.

## Root Cause

The backend registration request model requires:

- `username` length between 3 and 50 characters.
- `password` length of at least 6 characters.

The submitted username only had 2 characters, so backend validation rejected it.

## Process

1. Confirmed that frontend registration and login requests are routed through `/api/v1`.
2. Confirmed that Vite proxies requests to `http://localhost:8080`.
3. Called the backend registration endpoint directly to verify the response.
4. Traced the failure to the `RegisterRequest` username length constraint.

## Impact

- Only affects how the registration form validation failure is understood.
- No backend business logic changes were required.
- No database changes were required.

## Risks

- The frontend still collapses backend validation failures into a generic “registration failed” message, which is not very descriptive.
- More user-friendly error mapping can be added later, but that is outside the scope of this fix.

## Validation

- The backend registration endpoint can return `200 success`.
- Registration works when the username has at least 3 characters.

## Conclusion

This was not a missing preset account. The actual issue was a too-short username. Going forward, make sure registration usernames have at least 3 characters.
