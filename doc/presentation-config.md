# Presentation config: analysis and plan

Status: analysis, nothing implemented. This resolves the README TODO:
an optional per-dataset presentation configuration that can hide,
rename and reorder label types, without the viewer learning what any
label type means. Written 2026-08-14.

## The problem

The viewer is deliberately ignorant. Every tag it renders — label
types, label tags, relation types and roles, parts of speech,
inflected-form tags — is an opaque string from the dataset's own
inventories, expanded by the build into a description but never
interpreted. That ignorance is what makes the viewer generic, and it
must survive this feature.

The cost of the ignorance is that every dataset renders with the same
neutral taste: every label type shows, in dataset order, under its own
name. A real dictionary wants curation. DanNet, for instance, might
want its ontological-type labels hidden, its domain labels first, and
a label type with an ugly internal name shown under a reader-facing
one. That is taste, it is dataset-specific, and today there is nowhere
to put it.

## The invariant, made concrete

"The viewer never learns what a label type means" cashes out as three
rules:

1. No tag string from any dataset ever appears in the viewer's source.
2. The config is keyed by the dataset's own tags; the viewer only ever
   compares them for equality.
3. Every operation the config can express is generic — a set
   membership test, a sort by list position, a string substitution —
   and would work identically on any other dataset's tags.

Anything that fails one of these rules (say, "render domain labels as
chips") is out of scope for the config and belongs in the CSS escape
hatch described below, where the dataset's own stylesheet can target
the tags it knows about.

## Decision 1: apply at run time, not build time

The config could be baked in by the data build or applied by the
viewer at render time.

Build time is tempting because the build already resolves everything
else. But it fails the way this file will actually be used: a curator
tweaking taste wants to edit two lines and reload, not re-shard a
hundred thousand entry files. It also muddies the current clean split,
where the build resolves *meaning* (descriptions, URIs, relation
attachment — facts from the dataset) and would now also apply *taste*.
Facts change when the dataset changes; taste changes on a whim.

Run time costs one extra fetch of a file measured in hundreds of
bytes, plus a per-render transform that is trivial next to what the
views already do. So: the viewer fetches `data/presentation.json`
alongside the manifest. A missing file is the normal case and means
"no opinions", which must render exactly as today.

## Decision 2: one generic ops model, not bespoke options

Three candidate shapes for the config:

**(a) Per-key attribute maps** —
`{"domain": {"name": "Domæne", "order": 3, "hidden": false}}`.
Explicit, but verbose, and numeric order keys mean renumbering
everything to insert one item.

**(b) Three parallel ops** —
`{"order": [...], "hide": [...], "rename": {...}}`. Each op is
independent, does one obvious thing, and any of them can be omitted.
Ordering is by array position, so inserting means inserting.

**(c) One ordered array of entries** — listing implies order, entry
props carry renames. Compact, but it conflates the ops: you cannot
reorder without listing everything, and hiding requires a flag on an
entry whose presence otherwise means "show".

Shape (b) wins on the "flexible yet simple" axis, with one addition:
an `unlisted` policy that says what happens to keys the `order` array
does not mention — `"after"` (the default: they follow, in dataset
order) or `"hide"`. That one word flips the config from a touch-up
tool into an allowlist: name three label types, hide the rest. It is
the cheapest possible "greatly alter" lever inside the data model.

The same ops apply to every keyed vocabulary the viewer renders, so
the config is a map of section name to ops, and one function
implements all of it. New sections cost a key name, not a mechanism.

## The format

Everything below is optional, including the file itself.

```jsonc
{
  // Label groups in the entry header and under each sense.
  // Keys are the dataset's own labelType tags.
  "labelTypes": {
    "order":    ["domain", "register"],
    "unlisted": "after",              // or "hide"
    "hide":     ["ontologicalType"],
    "rename":   {"domain": "emne"}
  },

  // Rows in the relations panel, keyed by relation type.
  "relationTypes": {
    "order":  ["synonymy", "hyponymy"],
    "hide":   ["memberOf"],
    "rename": {"synonymy": "synonymer"}
  },

  // Renames of the displayed role names inside relation rows.
  // The dt shows (or role type), so this addresses what the reader
  // sees; hide/order stay on relationTypes, the row's identity.
  "roles": {
    "rename": {"hyper": "overbegreb", "hypo": "underbegreb"}
  },

  // A dataset stylesheet, fetched from the data directory and
  // appended after the viewer's own. The big lever: see below.
  "css": "custom.css"
}
```

Semantics, precisely:

- `hide` beats `order`: a key in both is hidden.
- `order` lists first, in the given order; `unlisted` decides the rest.
  Sorting is stable, so unlisted keys keep dataset order among
  themselves — and, as a welcome side effect, labels of the same type
  become adjacent even when the dataset interleaves them (today
  `partition-by` splits such a group in two).
- `rename` changes only the displayed text. The description tooltip
  from the dataset stays, the underlying tag stays in the data
  attribute, and hidden keys need no rename.
- Unknown section names and tags that do not occur in the dataset are
  ignored without complaint, so one config can serve several builds of
  a changing dataset.
- A malformed file logs to the console and renders as no config;
  presentation trouble must never take down the dictionary.

## How it lands in the code

The trick that keeps the diff small: apply the config to the *entry
data*, not inside the views. One pure function per shape, called at a
single boundary, and the view tree stays almost untouched.

A new namespace `dk.cst.dmlex-viewer.presentation` (~50 lines):

- `present` — apply one ops map `{order hide rename unlisted}` to a
  sequence of maps keyed by `k`: drop the hidden, stable-sort by order
  position, assoc the rename as `:display`. This is the whole
  mechanism; every section reuses it.
- `present-entry` — thread `present` over the places an entry holds
  keyed lists: entry and sense `:labels` (by `:type`), entry and sense
  `:relations` (by `:type`, with `roles` renames assoc'd as
  `:display-role`).

Wiring, in `app.cljs`:

- `init` fetches `data/presentation.json` into a `:presentation` slot
  with a no-op error handler (absence is normal, like `:index-error`
  the slot is its own key).
- The `app` view renders `(entry-view (present-entry presentation
  entry))` — the one application point.
- `labels-view` and `relations-view` render `(or (:display x) …)` in
  their `<dt>`s and gain `data-type` (and `data-role`) attributes on
  their group `<div>`s.
- When the config has `:css`, `init` appends
  `<link rel="stylesheet" href="data/<file>">` once.

Nothing else changes. `results-view`, the search index, the build and
the data files are untouched; a dataset without a config renders
byte-identically to today.

## What "greatly alter" actually buys

The ops alone cover the README TODO. The reach comes from their
composition with the two hooks that cost almost nothing:

- **Allowlist mode** (`"unlisted": "hide"`) turns a maximal DMLex
  dataset into a minimal reader's dictionary: three label types, two
  relation rows, nothing else.
- **Data attributes plus a dataset stylesheet** hand the dataset full
  visual control without the viewer learning a thing. The viewer's CSS
  is already one flat file of custom properties; a `custom.css` can
  retheme colors and type wholesale, turn a specific label type into
  colored chips (`.labels div[data-type="register"] { … }`), move the
  relations panel visually, or hide any element the ops model does not
  address. The viewer ships the hooks; the dataset ships the taste.

Together those span the range from "rename one label type" to "looks
like a different product", which is the flexibility asked for, on top
of a config a curator can learn in one reading.

## Rejected alternatives

- **Layout/section reordering in the config** (move relations above
  senses, relocate labels): a templating DSL in JSON is a second view
  layer to maintain, and flex `order` in the dataset stylesheet covers
  the realistic cases. Revisit only with a concrete dataset that needs
  it.
- **Build-time application**: see Decision 1.
- **Per-entry overrides**: the config is per-dataset taste; per-entry
  presentation is dataset authoring and belongs in the data.
- **UI-string overrides** (`"all forms"`, `"No matches"`): the same
  rename machinery would work, but the strings carry `lang="en"`
  markup and localizing them properly is an i18n feature, not a
  presentation one. Noted as a possible later section, deliberately
  not in scope.

## Plan

Each step is small, independently shippable, and ends green under
`clojure -M:test` and the node tests.

1. **The mechanism.** Add `dk.cst.dmlex-viewer.presentation` with
   `present` and `present-entry`, plus `presentation_test.cljs`
   pinning: hide beats order, stable order with `unlisted` `"after"`
   and `"hide"`, rename to `:display`, empty ops as identity, and
   `present-entry` as identity on an entry when the config is empty.
   Pure functions only; no wiring yet.
2. **Label types end to end.** Fetch `data/presentation.json` in
   `init` (no-op on error), apply `present-entry` in `app`, render
   `:display` and `data-type` in `labels-view`. This alone closes the
   README TODO.
3. **Relations.** Extend `present-entry` to `:relations` with
   `relationTypes` ops and `roles` renames; render `:display` /
   `:display-role` and `data-type` / `data-role` in `relations-view`.
4. **The stylesheet hook.** Inject the `:css` link in `init`.
   Document the data attributes and the custom-property names as the
   supported styling surface.
5. **Docs.** Replace the README TODO with a "Present the data" section
   holding the annotated example config; update
   [code-walkthrough.md](code-walkthrough.md) with the new namespace
   and the changed call flow.

Rough size: ~50 lines of new cljs, ~15 lines of view diffs, ~60 lines
of tests, no build changes.
