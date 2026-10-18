# Morning checklist before the 13:00 BST presentation (18 Oct 2026)

Written at 05:00 BST while Ayush was asleep. Everything below is on `main` (origin and publish).

## 000. Demo loader, charts, outlines (09:20–09:40 BST, by voice on the b8a2570 instance)

Load data ▾ (beside Mic) shows *Macros demo — blobs* and *Nuclei demo — BBBC008 (DNA channel)*; captured open:
`docs/images/fidelis/2026-10-18-load-data-menu.jpg`. Nuclei demo by voice: "Find all the nuclei" → 168 (1.2 s), green
numbered outlines on the canvas (`…nuclei-outlines-bbbc008.jpg`); "Show me the nuclei" → flash; "Which one is the
biggest?" → object 145, 179 px²; "Show me the size distribution" → chart (`…nuclei-size-chart.jpg`); "Remove anything
smaller than 30 pixels" → 161; "Go back" → 168 again. Blobs chart: `…blobs-size-chart.jpg`. What to say for both demos:
docs/FIDELIS-DEMO-SCRIPT.md.

## 00. The clean pass (09:10 BST, fresh instance from main 5e756bd, nothing fixed in between)

Nine lines by real AWS voice in one go, empty History at the start:

| Said | Penny | Speech end → audio |
|---|---|---|
| "Open the blobs image." | "Opened blobs, 256 by 254." | 1.1 s |
| "Find all the nuclei." | "Found 67 nuclei, mean area 327 square pixels." | 1.1 s |
| "Which one is the biggest?" | "The largest is object 61, 848 square pixels." (flashed) | 1.8 s |
| "Go back." | "Undid: 67 objects → 0, Results cleared." | 1.7 s |
| "Run this macro." | "Ready to run 1 step; say yes to go ahead." | 2.4 s |
| "Yes." | "Ran the macro: 67 result rows, 1 new image." | 1.2 s |
| "Apply this analysis to the whole folder." | "Ready to run this on 5 images in imagej; say yes to go ahead." | 2.8 s |
| "Yes." | "Ran it on 1 image (4 stacks skipped); the results are in penny-folder-results.csv." | 0.6 s |
| "What am I looking at?" | "Blobs: 256 by 254, 8-bit; one channel; 67 objects outlined." | 2.5 s |

The CSV lists blobs 67 / 326.657 and the four stacks as skipped. The instance is left in that state for the demo.

## 0. Done at 08:10 BST — voice is back and the whole script passed by voice

Ayush authorised the renewal ("do it, ensure all money comes from aws credits"): `npm run budget:renew --
--authorized-new-budget` archived the ledger ($199.87 reserved, $2.54 estimated spend) and started a new $200 one;
no restart was needed (the gateway reads the ledger per request). Every request still re-proves eligible AWS credits
(STS, Billing GetCredits for Bedrock / Transcribe / Polly, Pricing) before it reserves; no OpenAI or Anthropic API key
is configured. By real AWS voice on the tip build: "Find all the nuclei" → 67 (1.1 s); "Which one is the biggest?" →
"The largest is object 61, 848 square pixels."; "Go back" → 67 → 0; "Run this macro" → wait; "Yes" → 67 replayed;
"Apply this analysis to the whole folder" → "Ready to run this on 5 images in imagej; say yes to go ahead." (1.7 s);
"Yes" → "Ran it on 1 image (4 stacks skipped); the results are in penny-folder-results.csv." — the CSV lists blobs
67 / 326.657 and the four stacks as skipped; only blobs.gif stayed open. "Why did you count this as a cell?" with the
pointer off the image → "Point at the object first: the pointer is not over the image." The first folder "yes" had
stalled on Fiji's "Process Stack?" for the sample stacks; the macro now records stacks as skipped. Still not seen on
screen: the Mac is locked.

## 1. Voice was blocked until the renewal (now done)

The $200 attempt-reserve ledger filled up during the overnight verification (1,780 reservations, $199.63; the
receipts' estimated real spend is about $2.53). Every Transcribe / Polly / Bedrock request is refused until a new
budget is authorised. This is the "$200 before waking" guard from docs/FUNDING.md, and nothing renews it on its own.

```bash
npm run budget:renew -- --authorized-new-budget
```

It archives the exhausted ledger next to itself with a reconciliation record and starts an empty $200 ledger
(refused if the $1,000 lifetime total would be exceeded). Then restart the companion (quit Penny in the menu bar and
relaunch, or `./script/build_and_run.sh`) so the gateway re-reads the ledger.

The 24-hour funding proofs were renewed at 04:31 BST and are valid until 04:31 BST on 19 Oct, so they need nothing.

## 2. What is running now (06:15 BST)

Fidelis from main `6d5f435` — the tip, every fix of the night included — with the gateway in the `fidelis` profile
(34 bridge operations, 37 tools); the companion's microphone is off. Typed on it after the relaunch: open blobs (0.4 s),
67 nuclei, "Apply this analysis to the whole folder" → "Ready to run this on 5 images in imagej; say yes to go ahead."
(28 ms), "Which one is the biggest?" → object 61, 848 px² (13 ms), go back → 0. Leave it as it is; after the budget
renewal only the companion needs restarting.

## 3. Act 2 by voice, verified tonight on the merged build

| Say | Penny | Time |
|---|---|---|
| "Open the blobs image." | "Opened blobs, 256 by 254." | 0.4 s |
| "Find all the nuclei." | "Found 67 nuclei, mean area 327 square pixels." | 1.1 s |
| "Go back." | "Undid: 67 objects → 0, Results cleared." | 1.7 s |
| "Run this macro." | "Ready to run 1 step; say yes to go ahead." | 1.4 s |
| "Yes." | "Ran the macro: 67 result rows, 1 new image." — blobs.gif keeps focus, 67 outlines back | 1.2 s |

Also verified by voice on the same build: "Which one is the biggest?" (flashes it: "The largest is object 61, 848
square pixels." — typed check on the final build), "Highlight the smallest cell", "Make the threshold stricter" (68),
"Remove anything smaller than 100 pixels" (56 remain), "What is the mean intensity?" (103), "Zoom in on the image",
"Make it brighter", "Threshold the image" (Otsu 121–255), "Convert to mask" (waits for "yes"), "Duplicate this
image", "What am I looking at?", "Export the macro", and a second "Go back" with nothing left: "nothing to go back to
on this image; a macro's changes are not taken back."

## 3b. Added after the voice outage (typed and suite-tested only, not yet by voice)

"Why did you count this as a cell?" / "What's this one?" with the mouse over an outline: Penny flashes it and says
"Object 12: 657 square pixels, 1.8 times the median, circularity 0.62 — large and irregular: possibly two touching
objects. Otsu threshold, no size floor." With the pointer away from the image she asks you to point at it first. Needs
a Fidelis relaunch from main `93c9d73` (the bridge jar changed).

"These should be separate" / "Those are actually two cells" with the mouse over a clump: Penny splits that outline
along its watershed into recorded, undoable ROI-list steps and says "Split object 12 into 2 (260 square pixels and
256 square pixels); 68 objects now." A single round object is refused ("does not separate"). Bridge-tested on a
dumbbell outline (2 pieces, undo restores the clump); not yet tried on blobs by voice.

"These are one cell" / "Merge these two" with the mouse over one of two touching outlines: the union replaces them
("Merged objects 4 and 8 into one of 520 square pixels; 66 objects now."), undoable. Bridge-tested on the split pieces.

The split was checked live on the relaunched instance (06:40): the two-lobed blob at (175, 100) → 2 pieces, 68 objects,
undo → 67; the largest blob (object 61) is refused as one object.

"Apply this analysis to the whole folder" (or "… to the BBBC008 folder"): Penny builds the session's steps into a
macro over every image of the folder, shows it ("Ready to run this on 12 images in the BBBC008 folder; say yes to go
ahead.") and after "Yes" writes `penny-folder-results.csv` (Image, Count, MeanArea) into that folder. Engine-tested on a
two-image folder segmented with the demo's own segment_cells (both counted like the session, CSV with two rows). The
first live try (05:45) hit run_macro's 32 kB limit because the session macro carries every outline of the segmentation
literally; the folder macro now keeps the rule and skips literal outlines and hand corrections. Live on the relaunched
cfbfb7a instance (06:0x): the typed line after blobs → 67 answered "Ready to run this on 5 images in imagej; say yes to
go ahead." in 29 ms (macro 2,266 characters, 67 outlines skipped). Typed "yes" cannot be
checked with the text-drive tool (each run is a new conversation); the confirmation path is engine-tested and is the
same one "Run this macro" → "Yes" used by voice.

## 3c. Every suite on the final tree (05:50 BST)

Fidelis 470/0, gateway 256/0 (37 tools; Act 2 and the corrections + folder flow as one conversation each on a Fidelis
stub), stand-alone Fiji bridge tests pass (pipeline contract incl. split at the pointer and apply_analysis to a
folder), companion `swift build` clean, `verification/test_verification.py` OK.

## 4. Seen on screen (09:06 BST, after the unlock)

`docs/images/fidelis/2026-10-18-final-build-after-voice-folder-run.jpg`: the final build after the voice folder run —
Penny strip, blobs.gif with its 67 numbered outlines, the ROI list, History with the folder run's steps, the generated
macro. `docs/images/fidelis/2026-10-18-final-build-plan-card.jpg`: a plan waiting for "yes" ("Convert to mask." by
voice): the card quotes the request, shows the exact macro line and the policy's reasons, with Cancel / Confirm and
run; "No." by voice cancelled it. Two things the screen showed that were then fixed or noted: a long session's macro
(every outline of every segmentation is in it) can exceed run_macro's 32 kB — "Run this macro" now says so instead of
a validation error — and the folder run replaces the Results table (its own per-image rows); the ROI list stays.

## 4b. Not seen with eyes overnight

The display was asleep from about 04:30, so window captures came back black. The ROI counts, focus and History were
read through the bridge instead. Open Fidelis once before the demo and look: strip under the command bar, outlines
on the canvas after "Find all the nuclei", the plan card during "Run this macro".
