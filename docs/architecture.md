# Architecture and next steps

## Current scaffold

The React/TypeScript interface runs on Vite at `127.0.0.1:5173`. Vite proxies `/api` to FastAPI at `127.0.0.1:8000`. `GET /api/health` verifies the connection. The development proxy keeps browser requests on the same origin without broad CORS rules.

The Python package is installed in editable mode from `backend/src`. Python configuration lives at the repository root. Frontend dependencies and its lockfile live under `frontend/`.

This setup uses native Windows and has Fiji installed at `C:\Users\arnav\Documents\dev\Fiji`. WSL is not required. The production frontend build is a static artifact; production API routing/hosting is not implemented.

## Proposed implementation sequence

1. Select one concrete image-analysis workflow and create synthetic input with expected measurements.
2. Verify the selected scientific workflow in Fiji, record its plugin versions and expected measurements. Basic Fiji macro execution is already verified.
3. Add a Fiji adapter with an explicit executable path, validated macro parameters, timeouts, captured logs, and output isolation. Keep it behind a Python service boundary.
4. Add input selection, workflow settings and a single-image preview with clear success/failure feedback.
5. Add a batch job model, progress, cancellation, repeatable outputs and a run manifest.
6. Add voice as an optional way to fill workflow settings, with a visible review step before running analysis.
7. Test the full workflow and prepare a demo using redistributable fixtures.

These are planning notes, not implemented features or a recovered full specification. The linked prior conversation exposed user messages but its detailed assistant briefs were unavailable in the retrieved history. Confirm the workflow and success criteria before expanding the product.

## Data and configuration

Keep small synthetic inputs in `fixtures/`; keep real datasets and generated results in ignored `data/` and `outputs/`. Never overwrite inputs during analysis. The ignored `.env` configures `FIJI_EXECUTABLE` for `scripts/check_fiji.py`. The current health-only API does not invoke Fiji.

## Development references

- [Vite getting started](https://vite.dev/guide/)
- [FastAPI first steps](https://fastapi.tiangolo.com/tutorial/first-steps/)
