# Tests

`backend/` contains API contract tests. Run `.\.venv\Scripts\python.exe -m pytest` from the repository root.

The default suite skips real upstream tests. Set FIDELIS_INTEGRATION=1 to exercise actual speech recognition, Fiji image processing and replay. See docs/verification.md for evidence and limits. The frontend build checks TypeScript and the production bundle; interactive browser checks are documented separately.
