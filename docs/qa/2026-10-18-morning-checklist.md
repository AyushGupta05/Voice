# Morning checklist before the 13:00 BST presentation (18 Oct 2026)

Written at 05:00 BST while Ayush was asleep. Everything below is on `main` (origin and publish).

## 1. Voice is blocked until you run one command

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

## 2. What is running now

Fidelis from main `01dd27a` (pid 94077) with the gateway in the `fidelis` profile; the companion's microphone is off.
Leave it, or relaunch from the tip with `./script/run-fidelis.sh` after killing the old one.

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

## 4. Not seen with eyes tonight

The display was asleep from about 04:30, so window captures came back black. The ROI counts, focus and History were
read through the bridge instead. Open Fidelis once before the demo and look: strip under the command bar, outlines
on the canvas after "Find all the nuclei", the plan card during "Run this macro".
