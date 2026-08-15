# Apple Dictionary export: analysis and migration plan

Status: plan steps 1–3 are implemented (2026-08-15) as
`dk.cst.dmlex-viewer.appledict`; steps 4–5 wait for the
presentation-config feature. The question: DanNet's commit
`da3dc97` ("WIP: appledict MVP") added a 349-line namespace
(`dk.cst.dannet.db.export.appledict`) plus a 146-line CSS file that
export DanNet to the Dictionary.app format — consuming the DMLex
intermediate structure and adapting this project's stylesheet. Does
that conversion belong here instead, as a second build step over any
DMLex JSON file? Written 2026-08-15.

## Short answer

Yes. The conversion is DMLex in, presentation out — exactly this
project's domain, and the opposite of DanNet's (RDF in, DMLex out).
Nearly everything DanNet-specific in the MVP turns out to be either
data the DMLex export already carries, or hand-rolled presentation
config of precisely the shape planned in
[presentation-config.md](presentation-config.md). The migration makes
the converter generic, hands the taste to configuration, single-sources
the CSS in the repo that owns the design, and gives every DMLex
dataset a Dictionary.app export for free.

## The alignment target

The conversion is not a port of the MVP's output. The target is the
*viewer's* presentation, adapted to Dictionary.app conventions
(`d:priority` tiers, `x-dictionary:` links, a narrow popover layout)
without looking out of place there. Where the MVP and the viewer
render differently, the viewer wins. The clearest example is the
inflected forms: the MVP prints the full forms, while the viewer
compresses them through `affix` to dictionary shorthand (`-t` for
*mennesket*) with deduplication — and that is what Dictionary.app
should show too. The full forms still do their two structural jobs:
every one becomes a `d:index` term so lookups on inflected forms keep
working, and the full paradigm stays available behind an "all forms"
disclosure exactly as in the viewer (Dictionary.app renders with
WebKit, which supports `<details>`). Display follows the viewer's
taste; search and completeness keep the full data.

## What the MVP is

The DanNet namespace emits a Dictionary Development Kit source project:
one `d:dictionary` XML file (a string-based hiccup renderer, because
the DDK's mixed-namespace XHTML defeats clojure.data.xml), a CSS file,
an Info.plist and a Makefile. Building the final `.dictionary` bundle
still requires Apple's DDK and a Mac (`make && make install`); that is
true wherever the converter lives and does not change here.

Per entry it renders: `d:index` terms for the headword and every
inflected form, the headword with homograph number, the part of
speech, an inflections line, labels as run-in `dl`s, and numbered
senses with indicator, definitions, examples, labels and relation
links (`x-dictionary:r:` URLs). Secondary content carries
`d:priority="2"` so the small Look Up panel shows only the core.

## Why the coupling dissolves

Walking through every DanNet-specific piece of the MVP:

- **`pos-da`** (tag → Danish name) is built from
  `dmlex/part-of-speech-tags` — which the export serializes as
  `partOfSpeechTags` in the JSON. A generic converter reads the
  descriptions from the resource itself, exactly as
  [build.clj](../src/dk/cst/dmlex_viewer/build.clj) already does.
- **`prefix/dn-uri`** builds the synset link on wordnet.dk. But the
  export gives every synset labelTag a `sameAs` array holding that
  same URI (plus ILI links). Rendering a label with `(first sameAs)`
  as its link — which the viewer's `->label` already does — reproduces
  the link with zero DanNet knowledge.
- **`release/to`** (the version, for Info.plist) is `dc:issued` in the
  `metadata.json` that DanNet now ships next to the DMLex JSON — a
  file whose own docstring says it exists "so that e.g. a DMLex viewer
  can consume it". It also carries `dc:title`, `dc:language`,
  `dc:publisher`, `dc:rights`, `dc:license`, `dc:description` and
  `dc:source` — nearly the whole Info.plist, and most of a generic
  front-matter page.
- **`label-key-da`** is an ordered allowlist with Danish renames,
  where unlisted label types do not render. That is literally
  `{"order": [...], "rename": {...}, "unlisted": "hide"}` from the
  presentation-config design — the MVP hand-rolled the config this
  project already planned.
- **`role-da`** (synonym → "synonymer", untranslated roles keep their
  name) is the planned `roles` rename section, fallback included.
  "Synonyms sort first" is `relationTypes` order.
- **The front-matter prose and bundle identity** are dataset voice,
  not mechanism: config plus metadata.json.

What does *not* dissolve — three genuine specials, discussed below:
the sentiment pairing, the `unormeret` form styling, and the
hand-written front matter.

## What migrates, and into what

**Verbatim (generic already):** `escape`, `xml-name`, `hiccup->xml`,
`->index`, the `d:entry` skeleton, `write-xml!` (streaming, one entry
per line), the Makefile template, the export orchestration. These now
live in `dk.cst.dmlex-viewer.appledict` beside build.clj.

**Replaced by existing machinery here:** the MVP's `->context` and
`relation-links` duplicate a weaker version of build.clj's `->env` and
`relation-rows` — the MVP groups other members by role and drops
same-entry members, but lacks the multi-role co-member exclusion and
the row merging the viewer already gets right. The converter instead
renders the resolved output of `->entry-file` itself, so
`->env`/`relation-rows`/`->label`/`affix` (pure and tested) serve both
surfaces and the two outputs stay consistent. The dedup-by-short-form
rule of the inflections line comes from `distinct-by`, moved from the
frontend into the shared `shared.cljc`.

**Parameterized:** `info-plist` reads metadata.json with config
overrides (`CFBundleIdentifier` derived from `dc:identifier`, version
from `dc:issued`, copyright from `dc:rights`, and so on); a generic
front-matter entry is assembled from `dc:description` (in the resource
language), `dc:source` and `dc:license`, with an optional
config-supplied HTML file replacing it for datasets that want authored
prose, as DanNet does.

**Moved into presentation config:** the Danish renames, the label-type
order and allowlist, the role names, the relation ordering. One taste
file drives both the web viewer and the Dictionary.app export — which
considerably strengthens the case for the presentation-config plan,
since it now has two consumers on two platforms.

## The three specials

1. **Sentiment pairing.** The MVP merges the `sentiment` and
   `sentimentValue` label types into one row: "positiv (1.5)". No
   generic op covers "pair a label type with its qualifier type".
   MVP of the migration: render them as two adjacent rows (config
   `order` makes them adjacent), and accept the small polish loss. If
   it grates, a later `"combine": [["sentiment", "sentimentValue"]]`
   op rendering "first (second)" is generic enough to defend, but it
   should wait for a second dataset that wants it.
2. **`unormeret`.** Forms outside the spelling norm get a class and a
   CSS asterisk. Generically, a form with labels can carry
   `data-labels` (or the label tags as a class list) and the
   config-supplied extra stylesheet does the rest — same escape hatch
   as the web viewer's `data-type` attributes.
3. **Front matter.** Generic assembly from metadata.json covers the
   licence and description; DanNet's authored Danish prose (the COR
   asterisk explanation) moves to a small HTML fragment referenced
   from the config.

All three therefore land in the presentation-config feature rather
than in the converter: the migration MVP ships with the plain
defaults, and the polish returns as config once that feature exists.

## The CSS story

The copied `DanNet.css` is not a fork of the whole stylesheet but an
adaptation: same `:root` tokens, same small-caps metadata voice, same
sense numbering, but run-in `dl`s instead of the absolute-positioned
key column (right for a narrow popover), a dark-mode block (the web
viewer is deliberately light-only), WebKit-only assumptions, and no
search/results/colophon rules.

So "bundle the relevant CSS directly from this viewer" means a
three-file split along the one seam where drift would be a real bug:

- `public/css/tokens.css` — the shared `:root` block: the palette and
  the two font stacks, i.e. the visual identity. The viewer-only
  tokens stay here too, marked with a comment, so one file shows the
  whole palette.
- `public/css/style.css` — the web rules, unchanged minus its `:root`
  block; `index.html` links `tokens.css` first.
- `resources/appledict/style.css` — the Dictionary.app rules,
  including the dark-mode token override (deliberately not shared:
  the web viewer is light-only, and a shared dark block would leak
  into it via `prefers-color-scheme`).

The converter concatenates `tokens.css`, the appledict rules, and the
dataset's optional extra CSS (the same `"css"` hook as the web viewer)
into the single file the DDK wants, so the bundle still ships one
stylesheet.

The remaining overlap between the two rule sets — sense numbering,
headword, panel, all with different values — stays duplicated on
purpose. The values differ because the mediums differ (a 40rem page
against a narrow popover), and parameterising the shared structure
with more custom properties would let a web tweak silently reflow
Dictionary.app, where the regression is invisible until a bundle is
rebuilt and reinstalled. Some forty knowingly duplicated lines are
cheaper than that coupling.

## Interplay with the presentation-config plan

Two amendments to [presentation-config.md](presentation-config.md):

- The `present` mechanism (step 1) becomes a **`.cljc`** namespace, so
  the JVM converter and the ClojureScript viewer apply the identical
  ops. This is a genuine cross-platform case, not speculation.
- The config grows an **`appledict` section** for what only the bundle
  needs: identifier override, extra CSS file, front-matter HTML file.
  The web viewer ignores it; the ops sections are shared.

The converter works without any config (plain descriptions, dataset
order, generic front matter), exactly as the viewer does.

## What DanNet keeps and sheds

Sheds: `appledict.clj` and the copied `DanNet.css` — deleted once this
lands. DanNet's export pipeline already produces everything the
converter consumes: `dannet-dmlex.json` plus `metadata.json`.

Keeps: a `presentation.json` (plus front-matter fragment) checked in
next to DanNet's export code and shipped inside the DMLex export zip,
expressing today's `label-key-da`/`role-da` taste in config form
(decision 3 in [presentation-config.md](presentation-config.md)). The
DanNet workflow becomes: export DMLex, then in this repo
`clojure -M:appledict datasets/dannet-dmlex.json`.

## Plan

Steps 1–3 are done: the full DanNet dataset (62,028 entries) exports,
builds with the DDK from Additional Tools for Xcode 26.6, and installs
into Dictionary.app, where the affixed inflections, the paradigm
disclosure, the index terms for full forms and the relation links all
render as intended (see the screenshot in the README).

1. **The emitter.** Move `escape`, `xml-name`, `hiccup->xml` into
   `dk.cst.dmlex-viewer.appledict` with tests (escaping, attribute
   handling, self-closing, the `:d/`-prefix convention).
2. **Viewer-aligned entry rendering.** `->entry` over the resolved
   output of the existing `->env`/`->label`/`relation-rows`, mirroring
   `entry-view`'s decisions: `affix` short forms with deduplication on
   the inflections line, the full paradigm behind an "all forms"
   `<details>` disclosure, the same sense layout, and full forms as
   `d:index` terms with `d:priority` tiers on the secondary content.
   Test against the small resource fixture in
   [build_test.clj](../test/dk/cst/dmlex_viewer/build_test.clj).
3. **The bundle.** `write-xml!`, Info.plist from metadata.json (looked
   for next to the input JSON) with config overrides, generic front
   matter, Makefile, and `resources/appledict/style.css` (adapted from
   DanNet.css, tokens synced with style.css). Add the `:appledict`
   alias: `clojure -M:appledict <dmlex.json> [<out-dir>]`, DDK path as
   an optional flag with Apple's default location.
4. **Config consumption.** Depends on presentation-config steps 1–3
   (as amended: `.cljc`). Apply the shared ops to label types, roles
   and relation ordering; read the `appledict` section.
5. **DanNet adoption.** Author DanNet's `presentation.json` and
   front-matter fragment, build the bundle from the real
   `dannet-dmlex.json`, and review it in Dictionary.app side by side
   with the viewer, then delete the DanNet namespace and CSS copy.

Steps 1–3 needed nothing from the presentation-config work and already
produce a usable (plain-taste) dictionary; step 4 is where the Danish
polish returns. The MVP's XML is a coverage reference for step 5 —
a check that no content went missing unintentionally — not an output
to match: the rendering deliberately differs wherever the viewer's
presentation is better, starting with the affixed inflected forms.
