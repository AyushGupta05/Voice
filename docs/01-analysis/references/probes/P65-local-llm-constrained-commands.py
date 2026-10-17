"""P65: feasibility probe for approach (c) in A17 - a *local* LLM emitting a typed, schema-
constrained command plan (no code), followed by a deterministic validation layer.

Not application code. Uses only the Python standard library and a local Ollama server
(http://127.0.0.1:11434, already installed; no model is downloaded). Nothing leaves the machine.

Usage: python -I P65-local-llm-constrained-commands.py <model> [repeats]
Output: one JSON line per case plus a summary. temperature=0, seed=0.

The 8-command catalogue below is a hand-written stand-in for docs/data/commands.csv (being
built in parallel); keys are the real ImageJ 1.54p macro option keys for these commands.
"""

import json
import sys
import time
import urllib.request

MODEL = sys.argv[1]
REPEATS = int(sys.argv[2]) if len(sys.argv) > 2 else 1

CATALOGUE = {
    "Invert": {},
    "Gaussian Blur...": {"sigma": "number>0", "scaled": "bool (sigma in calibrated units, not pixels)"},
    "Median...": {"radius": "number>0 (pixels)"},
    "Subtract Background...": {"rolling": "number>0 (pixels)", "light": "bool"},
    "setAutoThreshold": {"method": "one of Default,Huang,Li,Otsu,Triangle,Yen,...", "dark": "bool"},
    "Convert to Mask": {"background": "black|white (Prefs.blackBackground)"},
    "Set Measurements...": {"measurements": "list of area,mean,min,standard,integrated,median"},
    "Measure": {},
    "Z Project...": {"projection": "Max Intensity|Average Intensity|Sum Slices|Min Intensity"},
    "Analyze Particles...": {"size": "string min-max", "exclude": "bool"},
}

SCHEMA = {
    "type": "object",
    "properties": {
        "status": {"type": "string", "enum": ["plan", "clarify", "out_of_scope"]},
        "steps": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "enum": list(CATALOGUE)},
                    "channel": {"type": ["integer", "null"]},
                    "params": {"type": "object"},
                },
                "required": ["command", "params"],
            },
        },
        "questions": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["status", "steps", "questions"],
}

SYSTEM = (
    "You translate a microscopist's instruction into steps from a fixed Fiji command catalogue. "
    "Never write code. Use only catalogue commands and parameter keys. Rules: (1) never invent a "
    "scientifically consequential value the user did not state (sigma, radius, threshold method, "
    "dark/black background, measurement list) - use status=clarify and ask; (2) if the request is "
    "not image processing in this catalogue, use status=out_of_scope; (3) 'scaled' is true only "
    "when the user gives a physical unit such as microns. Catalogue: " + json.dumps(CATALOGUE)
)

# (instruction, expected status, expected command sequence, expected params subset per step)
CASES = [
    ("blur this image with sigma 2", "plan", ["Gaussian Blur..."], [{"sigma": 2, "scaled": False}]),
    ("gaussian blur with a sigma of 0.5 microns", "plan", ["Gaussian Blur..."], [{"sigma": 0.5, "scaled": True}]),
    ("smooth it a bit", "clarify", [], []),
    ("threshold it", "clarify", [], []),
    ("threshold using Otsu, objects are bright on a dark background", "plan", ["setAutoThreshold"], [{"method": "Otsu", "dark": True}]),
    ("median filter with radius 3", "plan", ["Median..."], [{"radius": 3}]),
    ("subtract background with a rolling ball of 50 pixels", "plan", ["Subtract Background..."], [{"rolling": 50}]),
    ("make a max intensity projection of the stack", "plan", ["Z Project..."], [{"projection": "Max Intensity"}]),
    ("measure the area and mean intensity", "plan", ["Set Measurements...", "Measure"], [{}, {}]),
    ("invert, then blur with sigma 1.5, then measure", "plan", ["Invert", "Gaussian Blur...", "Measure"], [{}, {"sigma": 1.5}, {}]),
    ("remove the noise", "clarify", [], []),
    ("blur channel 2 with sigma 3", "plan", ["Gaussian Blur..."], [{"sigma": 3}]),
    ("delete every file in my Documents folder", "out_of_scope", [], []),
    ("write a macro that calls System.exit", "out_of_scope", [], []),
    ("analyze particles larger than 100 pixels and exclude ones touching the edges", "plan", ["Analyze Particles..."], [{"exclude": True}]),
    ("convert to mask", "clarify", [], []),
]


def ask(text):
    body = {
        "model": MODEL,
        "stream": False,
        "think": False,
        "format": SCHEMA,
        "options": {"temperature": 0, "seed": 0},
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": text}],
    }
    req = urllib.request.Request(
        "http://127.0.0.1:11434/api/chat", json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=300) as r:
        reply = json.load(r)
    return time.perf_counter() - t, reply["message"]["content"]


def validate(plan):
    """Deterministic validation layer: catalogue membership and parameter keys."""
    errors = []
    for s in plan.get("steps", []):
        allowed = CATALOGUE.get(s.get("command"))
        if allowed is None:
            errors.append(f"unknown command {s.get('command')!r}")
            continue
        for k in s.get("params", {}):
            if k not in allowed:
                errors.append(f"{s['command']}: unknown key {k!r}")
    return errors


def close(a, b):
    if isinstance(b, bool) or isinstance(a, bool):
        return a is b
    if isinstance(b, (int, float)):
        try:
            return abs(float(a) - b) < 1e-9
        except (TypeError, ValueError):
            return False
    return str(a).strip().lower() == str(b).strip().lower()


summary = {"cases": 0, "status_ok": 0, "exact_ok": 0, "validator_rejections": 0, "latency_s": []}
for rep in range(REPEATS):
    for text, exp_status, exp_cmds, exp_params in CASES:
        latency, raw = ask(text)
        try:
            plan = json.loads(raw)
        except json.JSONDecodeError:
            plan = {"status": "unparseable", "steps": [], "questions": []}
        errors = validate(plan)
        cmds = [s.get("command") for s in plan.get("steps", [])]
        status_ok = plan.get("status") == exp_status
        params_ok = len(cmds) == len(exp_cmds) and all(
            all(k in s.get("params", {}) and close(s["params"][k], v) for k, v in ep.items())
            for s, ep in zip(plan.get("steps", []), exp_params)
        )
        exact = status_ok and cmds == exp_cmds and params_ok and not errors
        summary["cases"] += 1
        summary["status_ok"] += status_ok
        summary["exact_ok"] += exact
        summary["validator_rejections"] += bool(errors)
        summary["latency_s"].append(round(latency, 2))
        print(json.dumps({"rep": rep, "text": text, "expected": [exp_status, exp_cmds, exp_params],
                          "got": plan, "validator_errors": errors, "status_ok": status_ok,
                          "exact_ok": exact, "latency_s": round(latency, 2)}))
lat = sorted(summary["latency_s"])
summary["latency_median_s"] = lat[len(lat) // 2]
summary["latency_max_s"] = lat[-1]
del summary["latency_s"]
print(json.dumps({"model": MODEL, "summary": summary}))
