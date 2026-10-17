"""Local API entry point. Fiji execution will be added in a later milestone."""

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="Click to Batch", version="0.1.0")


class HealthResponse(BaseModel):
    status: str
    service: str


@app.get("/api/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service="click-to-batch")
