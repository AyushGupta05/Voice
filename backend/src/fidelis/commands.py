"""A deliberately small, deterministic command grammar; no arbitrary code execution."""

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Action(BaseModel):
    model_config = ConfigDict(extra="forbid")
    operation: Literal["invert", "blur", "threshold", "measure"]
    sigma: float | None = Field(default=None, ge=0.1, le=10, allow_inf_nan=False)
    level: int | None = Field(default=None, ge=0, le=255, strict=True)

    @model_validator(mode="after")
    def parameters(self):
        if (self.operation == "blur") != (self.sigma is not None):
            raise ValueError("Only blur requires sigma")
        if (self.operation == "threshold") != (self.level is not None):
            raise ValueError("Only threshold requires level")
        return self


class Workflow(BaseModel):
    model_config = ConfigDict(extra="forbid")
    actions: list[Action] = Field(min_length=1, max_length=12)


def parse_command(text: str) -> Workflow:
    normalized = text.lower().strip().rstrip(".!?")
    normalized = re.sub(r"\s+", " ", normalized)
    actions = []
    for clause in re.split(r",? (?:and then|then) ", normalized):
        if re.fullmatch(r"(?:please )?invert(?: the image)?", clause):
            actions.append(Action(operation="invert"))
        elif re.fullmatch(r"(?:please )?measure(?: the image)?", clause):
            actions.append(Action(operation="measure"))
        elif match := re.fullmatch(
            r"(?:gaussian )?blur(?: the image)?(?: with)? sigma (\d+(?:\.\d+)?)", clause
        ):
            actions.append(Action(operation="blur", sigma=float(match[1])))
        elif match := re.fullmatch(r"threshold(?: the image)?(?: at)? (\d+)", clause):
            actions.append(Action(operation="threshold", level=int(match[1])))
        else:
            raise ValueError(
                "Unsupported command. Try: invert the image; blur sigma 2; "
                "threshold at 128; measure the image. Join commands with 'then'."
            )
    return Workflow(actions=actions)


def action_macro(action: Action) -> str:
    if action.operation == "invert":
        return 'run("Invert");'
    if action.operation == "blur":
        return f'run("Gaussian Blur...", "sigma={action.sigma:g}");'
    if action.operation == "threshold":
        return (
            f"setThreshold({action.level}, 255);\n"
            'setOption("BlackBackground", true);\nrun("Convert to Mask");'
        )
    return (
        'run("Set Measurements...", "area mean min standard redirect=None decimal=6");\n'
        'run("Measure");'
    )


def recorded_macro(workflow: Workflow) -> str:
    return (
        "// Fidelis recording: run on an open 8-bit image in Fiji.\n"
        + "\n".join(action_macro(action) for action in workflow.actions)
        + "\n"
    )
