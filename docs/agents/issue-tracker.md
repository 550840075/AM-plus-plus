# Issue tracker: Local Markdown

Issues and specs for this repo live as Markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`
- Triage state is recorded as a `Status:` line near the top of each issue file; see `triage-labels.md` for role strings
- Comments and conversation history append under a `## Comments` heading

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/`, creating the directory if needed.

## When a skill says "fetch the relevant ticket"

Read the referenced file. The user will normally provide its path or issue number.

## Wayfinding operations

Used by `/wayfinder`.

- Map: `.scratch/<effort>/map.md`
- Child tickets: `.scratch/<effort>/issues/NN-<slug>.md`
- A ticket is blocked when its `Blocked by:` entries are unresolved
- The frontier is the first open, unblocked, unclaimed ticket by number
- Claim by setting `Status: claimed`
- Resolve by adding an `## Answer` section and setting `Status: resolved`
