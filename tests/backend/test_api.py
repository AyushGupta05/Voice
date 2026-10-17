from fastapi.testclient import TestClient

from fidelis.main import app

client = TestClient(app)


def test_untrusted_origin_cannot_launch_a_job():
    result = client.post(
        "/api/jobs",
        headers={"Origin": "https://example.com"},
        json={"actions": [{"operation": "invert"}]},
    )
    assert result.status_code == 403


def test_invalid_action_and_path_do_not_execute():
    assert client.post("/api/jobs", json={"actions": [{"operation": "shell"}]}).status_code == 422
    result = client.post(
        "/api/jobs", json={"actions": [{"operation": "invert"}], "image_id": "../secret"}
    )
    assert result.status_code == 404
    assert client.get("/api/jobs/not-a-job").status_code == 404


def test_invalid_image_upload():
    assert client.post("/api/images", content=b"not an image").status_code == 422
    assert client.post("/api/images", content=b"").status_code == 422


def test_preview_has_no_execution_side_effect():
    result = client.post("/api/commands/preview", json={"text": "blur sigma 2"})
    assert result.status_code == 200
    assert result.json()["actions"][0]["sigma"] == 2
    assert "Gaussian Blur" in result.json()["macro"]
