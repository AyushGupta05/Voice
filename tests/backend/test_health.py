"""Verify the API contract consumed by the frontend."""

import asyncio

from httpx import ASGITransport, AsyncClient

from fidelis.main import app


def test_health_contract():
    async def get_health():
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://testserver"
        ) as client:
            return await client.get("/api/health")

    response = asyncio.run(get_health())
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "fidelis"}
