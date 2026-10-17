import pytest
from pydantic import ValidationError

from fidelis.commands import Action, parse_command, recorded_macro


def test_voice_punctuation_and_sequence():
    workflow = parse_command("Invert the image then measure the image.")
    assert [a.operation for a in workflow.actions] == ["invert", "measure"]
    assert 'run("Invert");' in recorded_macro(workflow)


@pytest.mark.parametrize(
    "text",
    [
        'invert; run("Quit")',
        "delete everything",
        "blur sigma 99",
        "threshold at 999",
        "invert the image then do something else",
        "",
        "blur sigma nan",
    ],
)
def test_rejects_unknown_or_unsafe_commands(text):
    with pytest.raises(ValueError):
        parse_command(text)


@pytest.mark.parametrize(
    "values",
    [
        {"operation": "invert", "sigma": 1},
        {"operation": "blur"},
        {"operation": "threshold", "level": True},
        {"operation": "blur", "sigma": float("nan")},
        {"operation": "measure", "script": "run(1)"},
    ],
)
def test_action_parameters_are_strict(values):
    with pytest.raises(ValidationError):
        Action(**values)
