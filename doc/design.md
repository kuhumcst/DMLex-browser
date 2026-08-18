# Design

dmlex-viewer is a generic viewer for DMLex 1.0 lexicographic
resources. Two display surfaces (a static web app and an Apple
Dictionary bundle) show the output of one resolution step. This
document records the decisions and principles that shape the code. It
leaves out anything that a code change can falsify. Function-level
detail lives in docstrings, the tests pin the tricky semantics, and
the how-to lives in the README.

## One resolution, dumb displays

The build reads a DMLex file once and resolves meaning up front. It
expands every tag through its inventory, attaches every relation to
the entries that it mentions, and compresses every inflected form to
its dictionary shorthand. Both display surfaces show these resolved
entries without further lookups. The heavy work happens once per
dataset release, and the display code stays simple. This simplicity
keeps the whole project small.

## No dataset knowledge

The viewer works on any DMLex 1.0 JSON file and knows nothing about
any particular dataset. Tags are opaque strings, and the code
compares them only for equality. This invariant decides where
features live. Anything that depends on what a tag means belongs to
the dataset or its config, never to this repository. DanNet, the
dataset that started this project, keeps its own taste in its own
repository. The boundary section below has the details.

## Meaning at build time, taste at run time

Facts change when the dataset changes. Taste changes far more often.
So the build resolves facts, and taste arrives as an optional
`presentation.json`. The dataset ships the file next to its data, and
the displays apply it at render or export time. A change to the
config never requires a rebuild of the data.

The config is a set of generic operations over the dataset's own
tags. The operations reorder, hide, rename, combine a type with its
qualifier type, select the field that a label shows, and gather
relation rows into groups. The keys stay strings throughout, because
a tag is not always a valid keyword. The same operations run on both
surfaces from one shared namespace. That is why the namespace is
cljc.

Two boundaries limit the config:

- Operations select among existing data. They never construct new
  text: no format templates and no regexes. Templates are the first
  step toward a rendering language. Regexes fail silently when they
  match too much, and they run on two different engines (JavaScript
  and Java). That breaks the promise that one config behaves the same
  on both surfaces.
- Pattern matching belongs at authoring time. The dataset's export
  code knows the full tag inventories, so it can generate the
  exhaustive literal config and warn about stale tags. The shipped
  config stays plain and deterministic, and also serves as a readable
  record of the vocabulary.

A missing config is the normal case, and the viewer then shows the
data neutrally. It ignores unknown sections and unknown tags without
complaint. Nothing disappears by accident. A qualifier without a host
stays an ordinary label, and unclaimed relation rows collect in a
fallback group unless the config hides them.

Member order is taste too. By default the members of a relation row
keep the listing order of the dataset. The DMLex spec asks a
conforming display to do exactly that. `"memberOrder": "collation"`
instead sorts them by the `obverseListingOrder` of each member and
then alphabetically, and the web viewer gives the reader a checkbox.
This use of `obverseListingOrder` is more liberal than the spec,
which reserves the field for the order of relations on a member's own
page. Here the `order` and `groups` of the config already do that
job, deliberately.

`"linkResolver"` bends the no-construction rule, deliberately and
minimally. Vocabulary URIs are identifiers first and links second.
Most serve raw RDF files, so a direct link helps nobody. A
linked-data project usually has a resource browser that shows any
URI. The operation is a fixed prefix plus one percent-encoded value:
no placeholders and no choices, so it cannot grow into a template
language. Both platforms pin the encoding to `encodeURIComponent`
semantics, so one config produces identical hrefs everywhere. URIs
already on the resolver's host stay direct.

## Two surfaces, one alignment rule

The web app is a ClojureScript SPA (Replicant, hash routing, a single
state atom, pure view functions). The Apple export writes the same
resolved entries as a Dictionary Development Kit source project. The
view functions mirror each other name for name, and the duplication
is a choice. The surfaces differ in link schemes, attribute
conventions and layout, and a shared view abstraction couples them
invisibly.

Where the surfaces can differ, the presentation of the web viewer
wins. Dictionary.app adapts the conventions, not the content. The
export marks secondary content with `d:priority`, so the compact Look
Up panel shows only the core. Every full inflected form becomes a
`d:index` term, so search continues to work while the entry shows the
affixed short forms.

## Static site, no server

The web app is a directory of files behind any static host. Hash
routing gives the site one crawlable URL, a consequence of the
no-server design. As a result, the site has no sitemap and no
server-side rendering. The JSON data files are the machine-readable
interface. Entry ids come from the dataset, so entry URLs are stable.
The site has no cookies, no analytics and no third-party requests,
and therefore needs no consent machinery. The README lists the
production headers.

## The visual language

The visual language is flat and typography-first: plain solid colours,
hairline rules in one shared colour, and one crimson accent. The
accent stays rare: the binding band, the headword, the sense numerals
and the group legends. A Transitional serif (Charter first) carries
everything, including the small-caps metadata voice. The page sits as
a white sheet on a grey desk. Charter is also in Firefox's base
font-visibility list, so strict tracking protection shows it rather
than a fallback.

Both surfaces read the palette and the font stacks from one shared
tokens file. Each surface duplicates the structural rules on purpose.
The mediums differ, and with shared structure a web tweak can
silently reflow Dictionary.app. There a regression stays invisible
until someone rebuilds a bundle. Visible duplication beats invisible
coupling.

The web viewer is light-only by choice. Dictionary.app adapts to dark
mode. The export's stylesheet pins `prefers-color-scheme` to light.
It derives the palette from `CanvasText`/`Canvas` mixes instead of a
dark media block, so the semantic colours follow the actual
appearance.

## Accessibility

The search field and its suggestions form an ARIA combobox: arrow
keys move the active option via `aria-activedescendant` while focus
stays in the field. Focus follows navigation, to the headword of the
new entry or back to the search field. The re-render removes the
element that held focus. Each view has one `h1`, and a status line
announces the result counts. Information in hover tooltips also
reaches assistive technology through visually hidden markup. The
document language comes from the manifest of the dataset at run time.
UI strings that stay English carry a `lang="en"` marker.

## UI translations

Most of what the viewer shows is dataset text, already in the
language of the resource. The views also prefer the dataset's
descriptions over technical tags where both exist, so a Danish
resource shows "substantiv" with "noun" in the tooltip. That leaves
the viewer's own strings, about two dozen in total, written in
English in the source: the search placeholder, the front-matter keys,
and the error messages.

A plain gettext setup translates them. The English string is the
lookup key and the fallback. `{n}` carries a count. The whole table
is a map from English to the target language. The viewer ships a
Danish table as `i18n/da.po` and uses it when the manifest says that
the resource is Danish. A dataset can add or override translations
with a `ui` section in its config or a `ui.po` file next to its data.
The web viewer also has a dropdown that selects among the bundled
languages, and localStorage remembers the choice per dataset. The
`ui` table of the dataset applies only while the chosen language is
the language of the resource, because its strings are in that
language. The po format was the choice because translation tools
(Poedit, Weblate) edit it directly. The pottery library that parses
it runs only on the JVM at build and export time. The web app
receives the tables at compile time.

`clojure -M:i18n` extracts the translatable strings from the source
and writes them to `i18n/template.pot`. If the template or the
bundled Danish file no longer matches the code, the tests fail.
Neither can go stale in silence. The extraction sees only string
literals (or a let-bound conditional over literals) in tr/en calls.
That is why the views sometimes repeat a string instead of an
abstraction.

DMLex 1.0 has a single description field per inventory tag, so one
export cannot be bilingual. A resource instead exports once per
language. The `langCode` of each export decides the rest: the dataset
text, the UI language, and the chrome of the Apple bundle. (The
export generates CSS overrides for the two strings that its
stylesheet shows as content.)

## Display over cleverness

Where a clever reduction can mislead, the display falls back to the
full form. The affix shorthand refuses multiword headwords, stem
changes and compound stems. A form with the same spelling as the
headword stays off the inflection line, but remains in the paradigm
table and the search index. The run-in inflection line and the full
paradigm overlap by design: one is for a quick scan, one is for
completeness.

## Web-standards audit

The last audit against
[The Website Specification](https://specification.website/) was on
2026-08-14. A future audit needs only the delta since then (the
changelog feed is at `https://specification.website/changelog/rss.xml`).
Four items are declined, with reasons: a skip link (only one control
precedes the main content), a sitemap and SSR (hash routing, see
above), fingerprinted asset names (the README tells hosts to use
`no-cache` instead), and JSON-LD (the data files serve agents
better). Four more items wait until the site has a stable public URL:
a custom 404 page, Open Graph tags, a canonical link, and `llms.txt`.

## The DanNet boundary

The taste of DanNet is its `presentation.json`, any custom stylesheet,
and its authored front matter. All of it lives in the DanNet
repository, next to the export code that invents the tags it names.
It ships inside the DMLex export zip together with `metadata.json`
(the Dublin Core file that both surfaces read for bundle identity and
front matter). The commit that renames a tag is the commit that
updates the config, and the export is the place to validate or
generate it. The workflow: export DMLex there, run the builds here.
Both builds take the export zip directly and find the DMLex JSON and
its companions inside it.
