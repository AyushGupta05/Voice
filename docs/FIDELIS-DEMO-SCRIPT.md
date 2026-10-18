# What to say — the Penny × Fidelis demo (18 October 2026)

Four minutes, hands on nothing but the mouse. Every line below was spoken to the real build this morning and answered
in about a second; the replies quoted are what Penny said. Speak naturally, pause a beat after each answer.

## Before you start (30 seconds)

1. Fidelis is up with Penny's strip under the command bar reading **Penny · Ready**.
2. Click **Demo** (top right, beside Mic). It opens `blobs.gif` through the engine and focuses it — the first step of
   History, so the replay later starts from a real "open". The strip says *Demo image blobs.gif is open; say "Find all
   the nuclei"*.
3. Click **Mic** once (it stays on for 30 minutes). Put the mouse somewhere on the image.

## Act 1 — it just does it (60 s)

> **"Find all the nuclei."**
> *Finding the nuclei.* → *Found 67 nuclei, mean area 327 square pixels.*
> Outlines appear on every blob, numbered; the ROI list fills; History shows the eight steps Fiji ran.

> **"Which one is the biggest?"**
> *The largest is object 61, 848 square pixels.* — that one flashes on the canvas.

> **"Show me the size distribution."**
> *Here is the size distribution: 67 objects, median 311 square pixels, from 24 to 848.* — a histogram opens beside
> the window.

What to say while it draws: *"This is the real Fiji — every step is a Fiji command, recorded in History, and the
numbers come from Fiji's own Results table. Nothing is invented."*

## Act 2 — the researcher is in charge (60 s)

Move the mouse onto the two-lobed blob near the middle-right (object 28, the one that looks like a peanut).

> **"Those are actually two separate cells."**
> *Separating them.* → *Split object 28 into 2 (340 square pixels and 334 square pixels); 68 objects now.*

Move the mouse onto a small blob near the edge.

> **"Why did you count this as a cell?"**
> *Object 1: 75 square pixels, 0.3 times the median, circularity 0.41 — small: near the size floor. Otsu threshold, no
> size floor.*

What to say: *"Corrections are not drawings on top — they are steps in the workflow. And when I ask why, the answer is
the actual measurement and the actual threshold."*

## Act 3 — trust: go back, replay, scale (90 s)

> **"Go back."**
> *Undid: 68 objects → 0, Results cleared.* — the outlines vanish; History keeps the record.

> **"Run this macro."**
> *Ready to run 1 step; say yes to go ahead.* — the plan card shows the exact macro Fidelis generated from History.

> **"Yes."**
> *Ran the macro: 67 result rows, 1 new image.* — the same 67 come back from a cold replay.

> **"Apply this analysis to the whole folder."**
> *Ready to run this on 5 images in imagej; say yes to go ahead.*

> **"Yes."**
> *Ran it on 1 image (4 stacks skipped); the results are in penny-folder-results.csv.*

What to say: *"Anything that runs code or touches more than this image waits for my yes — that is Fidelis's policy,
not Penny's mood. The macro it ran is the one in the editor; the CSV is in the folder; the stacks it could not treat
like this image are listed as skipped, not quietly mis-measured."*

## Close (20 s)

> **"What am I looking at?"**
> *Blobs: 256 by 254, 8-bit; one channel; 67 objects outlined.*

*"Voice first, hands free, on the real software, every step reproducible. That is Penny."*

## If something goes sideways

- Penny mishears: say it again; short lines land best ("Go back", "Yes").
- "Point at the object first" — the mouse is not over the image; move it onto an outline and repeat.
- A plan is waiting and you want out: **"No."** → *Cancelled; nothing ran.*
- Voice silent: the Penny strip shows the reason. Funding proofs are valid until 04:31 tomorrow; the attempt ledger
  has ~$190 of its $200 left. docs/qa/2026-10-18-morning-checklist.md has the recovery steps.
- Start over: click **Demo** again (it refocuses blobs), say **"Go back"** until the list is empty, or relaunch with
  `./script/run-fidelis.sh` for an empty History.
