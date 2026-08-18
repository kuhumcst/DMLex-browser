# Design

A generic viewer for DMLex 1.0 lexicographic resources, with two
display surfaces (a static web app and an Apple Dictionary bundle) fed
by one resolution step. This document records the decisions and
principles that shape the code. It leaves out anything a code change
would falsify: function-level detail lives in docstrings, the tricky
semantics are pinned by the tests, and the how-to lives in the README.

## One resolution, dumb displays

The build reads a DMLex file once and resolves meaning up front: every
tag expanded through its inventory, every relation attached to the
entries it mentions, every inflected form compressed to its dictionary
shorthand. Both display surfaces render these resolved entries without
further lookups. The heavy work happens once per dataset release, and
the display code stays simple, which is what keeps the whole project
small.

## No dataset knowledge

The viewer works on any DMLex 1.0 JSON file and knows nothing about
any particular dataset. Tags are opaque strings, compared only for
equality. This invariant decides where features live: anything that
depends on what a tag means belongs to the dataset or its presentation
config, never to this repository. The dataset this project grew out of
(DanNet) keeps its own taste in its own repository; see the boundary
section below.

## Meaning at build time, taste at run time

Facts change when the dataset changes; taste changes far more often.
So facts are resolved by the build, while taste arrives as an optional
`presentation.json` that the dataset ships next to its data and the
displays apply at render or export time. Editing it never requires a
rebuild of the data.

The config is a set of generic operations over the dataset's own tags:
ordering, hiding, renaming, combining a type with its qualifier type,
choosing which field of a label to display, and gathering relation
rows into titled groups. Its keys stay strings throughout, because
tags need not be valid keywords. The same operations run on both
surfaces from one shared namespace, which is why that namespace is
cljc.

Two boundaries limit the config:

- Operations select among existing data; they never construct new
  text. No format templates and no regexes. Templates are the first
  step toward a rendering language, and regexes both fail silently
  when they match too much and run on two different engines
  (JavaScript and Java), which would break the promise that one config
  behaves the same on both surfaces.
- Pattern matching belongs at authoring time. The dataset's export
  code knows the full tag inventories, so it can generate the
  exhaustive literal config and warn about stale tags. The shipped
  config stays plain and deterministic, and also serves as a readable
  record of the vocabulary.

A missing config is the normal case and renders neutrally; unknown
sections and unknown tags are ignored without complaint. Nothing is
lost by accident: a qualifier without a host stays an ordinary label,
and unclaimed relation rows trail in a fallback group unless
explicitly hidden.

Member order is taste too. By default the members of a relation row
keep the listing order of the dataset, which is what the DMLex spec
asks a conforming display to do; `"memberOrder": "collation"` sorts
them by each member's `obverseListingOrder` and then alphabetically
instead, and the web viewer offers the reader a toggle. This reads
`obverseListingOrder` more liberally than the spec, which reserves it
for ordering relations on a member's own page — a job the config's
relation `order` and `groups` already do here, deliberately.

`"linkResolver"` bends the no-construction rule, deliberately and
minimally. Vocabulary URIs are identifiers first and links second:
most serve raw RDF files, so sending readers there helps nobody, while
a linked-data project usually has a resource browser that renders any
URI. The op is a fixed prefix plus one percent-encoded value — no
placeholders and no choices, so it cannot grow into a template
language — and the encoding is pinned to `encodeURIComponent`
semantics on both platforms so one config renders identical hrefs
everywhere. URIs already on the resolver's host are left direct.

## Two surfaces, one alignment rule

The web app is a ClojureScript SPA (Replicant, hash routing, a single
state atom, pure view functions). The Apple export renders the same
resolved entries as a Dictionary Development Kit source project. Their
view functions mirror each other name for name, and the duplication is
a choice: the surfaces differ in link schemes, attribute conventions
and layout, and a shared view abstraction would couple them invisibly.

Where the surfaces could differ, the web viewer's presentation wins.
Dictionary.app adapts the conventions, not the content: secondary
content is marked with `d:priority` so the compact Look Up panel shows
only the core, and every full inflected form becomes a `d:index` term
so search keeps working while the display shows the affixed short
forms.

## Static site, no server

The web app is a directory of files behind any static host. Hash
routing gives the site one crawlable URL, a consequence of the
no-server design, and hence no sitemap and no server-side rendering.
The JSON data files are the machine-readable interface; entry ids come
from the dataset, so entry URLs are stable. No cookies, no analytics,
no third-party requests, and therefore no consent machinery.
Production headers are listed in the README.

## The visual language

Flat and typography-first: plain solid colours, hairline rules drawn
in a single shared colour, and one crimson accent used sparingly, for
the binding band, the headword, the sense numerals and the group
legends. A Transitional serif (Charter first) carries everything,
including the small-caps metadata voice; the page sits as a white
sheet on a grey desk. Charter is also in Firefox's base
font-visibility list, so strict tracking protection renders it rather
than a fallback.

The palette and font stacks live in one shared tokens file consumed by
both surfaces. The structural rules are duplicated per surface on
purpose: the mediums differ, and shared structure would let a web
tweak silently reflow Dictionary.app, where a regression stays
invisible until a bundle is rebuilt. Visible duplication beats
invisible coupling.

The web viewer is light-only by choice. Dictionary.app adapts to dark
mode, but it pins `prefers-color-scheme` to light while resolving
semantic colours by its actual appearance, so its stylesheet derives
the palette from `CanvasText`/`Canvas` mixes instead of a dark media
block.

## Accessibility

The search field and its suggestions form an ARIA combobox: arrow keys
move the active option via `aria-activedescendant` while focus stays
in the field. Focus follows navigation, to the new entry's headword or
back to the search field, because re-rendering removes the element
that held focus. Each view has one `h1`, a status line announces
result counts, and information shown in hover tooltips also reaches
assistive technology through visually hidden markup. The document
language comes from the dataset's manifest at run time; UI strings
that remain English are marked `lang="en"`.

## UI translations

Most of what the viewer shows is dataset text and is already in the
resource's language. The views also prefer the dataset's descriptions
over technical tags where both exist, so a Danish resource shows
"substantiv" with "noun" in the tooltip. That leaves the viewer's own
strings, about two dozen in total, written in English in the source:
the search placeholder, the front-matter keys, error messages and so
on.

These are translated with a plain gettext setup. The English string is
the lookup key and the fallback, `{n}` stands in for a count, and the
whole table is just a map from English to the target language. The
viewer ships a Danish table as `i18n/da.po` and uses it when the
manifest says the resource is Danish; a dataset can add or override
translations with a `ui` section in its presentation config or a
`ui.po` file next to its data. The web viewer also has a dropdown for
choosing among the bundled languages, stored per dataset in
localStorage; the dataset's own `ui` table only applies while the
chosen language is the resource's, since its strings are written in
that language. The po format was chosen because
translation tools (Poedit, Weblate) edit it directly. The pottery
library that parses it runs only on the JVM at build and export time;
the web app gets the tables baked in during compilation.

The list of translatable strings is written to `i18n/template.pot` by
`clojure -M:i18n`, which extracts them from the source. Tests fail if
the template or the bundled Danish file no longer matches what the
code uses, so neither can silently go stale. The extraction only sees
string literals (or a let-bound conditional over literals) in tr/en
calls, which is why the views sometimes repeat a string instead of
abstracting it away.

DMLex 1.0 has a single description field per inventory tag, so one
export cannot be bilingual. A resource is instead exported once per
language, and the `langCode` of each export decides the rest: the
dataset text, the UI language, and the Apple bundle's chrome (the
export generates CSS overrides for the two strings its stylesheet
renders as content).

## Display over cleverness

Where a clever reduction could mislead, the display falls back to the
full form: the affix shorthand gives up on multiword headwords, stem
changes and compound stems, and a form spelled like the headword stays
off the inflection line entirely but remains in the paradigm table and
the search index. The run-in inflection line and the full paradigm
overlap by design, one made for scanning and one for completeness.

## Web-standards audit

Audited against [The Website Specification](https://specification.website/)
on 2026-08-14; a future audit only needs the delta since then (the
changelog feed is at `https://specification.website/changelog/rss.xml`).
Declined, with reasons: a skip link (only one control precedes the
main content), sitemap and SSR (hash routing, see above),
fingerprinted asset names (the README tells hosts to use `no-cache`
instead), and JSON-LD (the data files serve agents better). Deferred
until the site has a stable public URL: a custom 404 page, Open Graph
tags, a canonical link, and `llms.txt`.

## The DanNet boundary

DanNet's taste, meaning its `presentation.json`, any custom stylesheet
and its authored front matter, is authored in the DanNet repository
next to the export code that invents the tags it names, and ships
inside the DMLex export zip alongside `metadata.json` (the Dublin Core
file both surfaces read for bundle identity and front matter). The
commit that renames a tag is the commit that updates the config, and
the export is the place to validate or generate it. The workflow:
export DMLex there, run the builds here. Both builds take the export
zip directly and find the DMLex JSON and its companions inside it.
