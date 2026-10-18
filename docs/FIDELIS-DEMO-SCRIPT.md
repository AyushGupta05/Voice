# What to say — the Penny × Fidelis demos (18 October 2026)

Hands on nothing but the mouse. Every line below was spoken to the real build this morning and answered in about a
second; the replies quoted are what Penny said. Speak naturally, pause a beat after each answer.

## Before you start (20 seconds)

1. Fidelis is up; the strip under the command bar reads **Penny · Ready**.
2. Click **Load data ▾** (top right, beside Mic) and pick the demo. The image opens through the engine — the first
   step of History, so the replay later starts from a real "open" — and the strip tells you the first line to say.
3. Click **Mic** once (it stays on for 30 minutes). Put the mouse on the image.

Two demos live in that menu. Do the **Nuclei** one first (it is the visual one), then **Macros** (the trust one).

---

## Demo 1 — Nuclei (Load data ▾ → *Nuclei demo — BBBC008*)

A 512 × 512 fluorescence image of human cell nuclei (DNA channel), 168 of them.

> **"Find all the nuclei."**
> *Finding the nuclei.* → *Found 168 nuclei, mean area 70 square pixels.*
> Green outlines appear on every nucleus, numbered; the ROI list fills; History shows the eight Fiji steps.

> **"Show me the nuclei."**
> *Here they are.* — every outline flashes on the canvas.

> **"Which one is the biggest?"**
> *The largest is object N, … square pixels.* — that nucleus flashes.

> **"Show me the size distribution."**
> *Here is the size distribution: 168 objects, median … square pixels, from … to ….* — the histogram opens beside
> the window.

> **"Remove anything smaller than 30 pixels."**
> *Segmenting cells.* → *… nuclei remain, all ≥ 30 pixel².* — the debris disappears, the count updates.

Move the mouse onto a nucleus that looks like two touching.

> **"Those are actually two separate cells."**
> *Separating them.* → *Split object N into 2 (… and … square pixels); … objects now.*

Move the mouse onto a small one.

> **"Why did you count this as a cell?"**
> *Object N: … square pixels, 0.4 times the median, circularity 0.7 — small: near the size floor. Otsu threshold,
> size floor 30.*

> **"Go back."** (twice if you want the split undone too)
> *Undid: …* — outlines and count follow History.

Say while it works: *"This is the real Fiji. Every step is a Fiji command, recorded in History; the numbers are Fiji's
own measurements. Corrections are steps in the workflow, not drawings on top — and when I ask why, the answer is the
measurement and the threshold, not an opinion."*

---

## Demo 2 — Macros (Load data ▾ → *Macros demo — blobs*)

> **"Find all the nuclei."**
> *Found 67 nuclei, mean area 327 square pixels.*

> **"Go back."**
> *Undid: 67 objects → 0, Results cleared.* — the outlines vanish; History keeps the record.

> **"Run this macro."**
> *Ready to run 1 step; say yes to go ahead.* — the plan card shows the exact macro Fidelis generated from History.

> **"Yes."**
> *Ran the macro: 67 result rows, 1 new image.* — the same 67 come back from a cold replay.

> **"Apply this analysis to the whole folder."**
> *Ready to run this on 5 images in imagej; say yes to go ahead.*

> **"Yes."**
> *Ran it on 1 image (4 stacks skipped); the results are in penny-folder-results.csv.*

> **"What am I looking at?"**
> *Blobs: 256 by 254, 8-bit; one channel; 67 objects outlined.*

Say: *"Anything that runs code or touches more than this image waits for my yes — that is Fidelis's policy. The macro
it ran is the one in the editor; the CSV is in the folder; what it could not treat like this image is listed as skipped,
not quietly mis-measured. Voice first, hands free, on the real software, every step reproducible."*

(On the BBBC008 folder — 24 single-plane images — "Apply this analysis to the whole folder" runs on all 24.)

---

## If something goes sideways

- Penny mishears: say it again; short lines land best ("Go back", "Yes").
- "Point at the object first" — the mouse is not over the image; move it onto an outline and repeat.
- A plan is waiting and you want out: **"No."** → *Cancelled; nothing ran.*
- Voice silent: the Penny strip shows the reason. Funding proofs are valid until 04:31 tomorrow; the attempt ledger
  has most of its $200 left. docs/qa/2026-10-18-morning-checklist.md has the recovery steps.
- Start over: **Load data ▾** again refocuses the image; say **"Go back"** until the list is empty, or relaunch with
  `./script/run-fidelis.sh` for an empty History.
