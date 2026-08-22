# Design

DMLex browser is a generic browser for DMLex 1.0 lexicographic
resources. Two display surfaces (a static web app and an Apple
Dictionary bundle) show the output of one resolution step. This
document records the decisions and principles that shape the code. It
leaves out anything that a code change can falsify. Function-level
detail lives in docstrings, the tests pin the tricky semantics, and
the how-to lives in the README and the pages it links.

## One resolution, dumb displays

The build reads a DMLex file once and resolves meaning up front. It
expands every tag through its inventory, attaches every relation to
the entries that it mentions, and compresses every inflected form to
its dictionary shorthand. Both display surfaces show these resolved
entries without further lookups. The heavy work happens once per
dataset release, and the display code stays simple. This simplicity
keeps the whole project small.

The build clears its output directories and writes every entry
fresh, rather than overwriting in place. No stale entry survives a
rename. This approach is also faster. On a copy-on-write filesystem,
overwriting a file costs several times what creating one does. For
DanNet that is the difference between two minutes and eleven.

## No dataset knowledge

The project works on any DMLex 1.0 JSON file and knows nothing about
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
tags. The operations reorder, hide and rename tags, combine a type
with its qualifier type, and select the field that a label shows.
They also move label types onto the part-of-speech line and gather
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

A missing config is the normal case, and the interface then shows the
data neutrally. It ignores unknown sections and unknown tags without
complaint. Nothing disappears by accident. A qualifier without a host
stays an ordinary label, and unclaimed relation rows collect in a
fallback group unless the config hides them.

Neutral relations render in the same titled box as configured groups,
under the generic "related" heading. Entry-level labels sit in the
same kind of box under the generic "about the word" heading. When the
config moves every entry label onto the part-of-speech line, the box
disappears.

A checkbox in the web app turns the config off for an entry, to
compare it with this neutral view. localStorage remembers the checkbox
choices per dataset, like the UI language choice.

Member order is taste too. By default the members of a relation row
keep the listing order of the dataset. The DMLex spec asks a
conforming display to do exactly that. `"memberOrder": "collation"`
instead sorts them by the `obverseListingOrder` of each member and
then alphabetically. A checkbox in the web app forces a strictly
alphabetical order, whatever the config prefers.

This use of `obverseListingOrder` is more liberal than the spec, which
reserves the field for the order of relations on a member's own page.
Here the `order` and `groups` of the config already do that job,
deliberately.

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

The web app is a ClojureScript SPA (Replicant, path routing, a single
state atom, pure view functions). The Apple export writes the same
resolved entries as a Dictionary Development Kit source project. The
rule between them: share decisions, duplicate markup. What to show is
pure data logic: which form represents a paradigm slot, what a tooltip
says. This logic lives in the shared namespace, where one fix reaches
both surfaces.

The view functions that emit the markup mirror each other name for
name, and that duplication is a choice. The surfaces differ in link
schemes, attribute conventions and layout, and a shared view
abstraction couples them invisibly.

The web views are cljc, but that is a different matter. The browser
and the data build render the same markup for the same surface. That
is what the rule asks for.

This coupling has two danger zones, and short comments mark them in
the code. The shared namespaces are the first zone. An edit there
changes both surfaces, but no automated check renders the Apple
bundle, so the change reaches Dictionary.app unseen. When one
surface must show different content, it stops calling the shared
function. A shared function never gets options for one surface. The
mirrored view pairs are the second zone: the two files differ on
purpose, so a verbatim copy of a view is never correct.

Where the surfaces can differ, the presentation of the web app
wins. Dictionary.app adapts the conventions, not the content. The
export marks secondary content with `d:priority`, so the compact Look
Up panel shows only the core. Every full inflected form becomes a
`d:index` term, so search continues to work while the entry shows the
affixed short forms.

## Static site, no server

The web app is a directory of files behind any static host. Every
entry URL is a real file, `entry/<id>/index.html`, which the data
build renders from the same views the browser renders. Nothing about
the site needs a server: a static host that serves `index.html` for a
directory URL is enough.

Two consequences follow. The site reads without JavaScript, and a
crawler sees the entries rather than an empty shell. Only the search
needs the app.

And every page can name the site root the same way. Each page carries
a `base` element that points at the root, and every link and fetch in
the code is relative to it. This keeps the site portable to a
subdirectory. Absolute paths fix it to one location.

Replicant has no hydration: the first client render replaces the
served markup with an identical rebuild. The app therefore waits for
the manifest and the first route before it renders. The reader never
sees a half-loaded page flash over the rendered one.

The JSON data files remain the machine-readable interface. Entry ids
come from the dataset, so entry URLs are stable. The site has no
cookies, no analytics and no third-party requests, and therefore needs
no consent machinery. [deploy.md](deploy.md) lists the production
headers.

## Data in, hiccup out

The views are pure functions from one value of the app state to
hiccup. Nothing in them reads the state atom, touches the DOM or
closes over an effect. The data build can therefore render them on the
JVM, and the tests can assert on their HTML without a browser.

Four conventions keep it that way.

The UI table and the URL scheme are ambient, because nearly every view
needs one or the other. To thread both through the tree adds a
parameter to almost every function. They travel in Replicant's alias
data instead, and two aliases read them.

`hiccup/tr` renders a chrome string in the element it is given. It
marks an untranslated one with `lang="en"`, so assistive technology
reads it in English. `hiccup/a` builds the link to an entry, and to a
sense of one, so the URL scheme lives in a single place.

An alias can only stand where an element stands. The few views that
translate an attribute value, such as an `aria-label` or a tooltip,
still take the table as an argument. They are all chrome views, one
call away from the root.

Event handlers and life-cycle hooks are data, never functions. One
dispatcher in the app namespace interprets them. This is what the
library asks for: a closure is a new value on every render, so
Replicant detaches and reattaches the listener each time. It is also
what makes the views cross-platform, since a function that scrolls or
focuses cannot live in a cljc file.

Navigation states its intention rather than performing it. A route
resolves to a reveal instruction. The instruction names the entry or
the sense that takes the focus. It also names what scrolls: the page
top, the entry, or the target itself.

The element it names carries the hook, and the dispatcher clears the
instruction once it has run. So no code reads the DOM in the hope that
a render already ran. The answer to "why did it scroll there" is a
value in the state.

To present an entry group, the code walks and sorts the whole of it.
This work happens when the reader navigates or changes a control, not
on every render. The state holds the entries as fetched and as
presented, and one function decides which config and which member
order the reader asked for.

## One page per homograph group

A dataset can split one headword into many entries with homograph
numbers. The web app shows the entries that share a headword and
a part of speech as one page, divided by hairline rules. Each entry
keeps its own number and its own stable URL. A URL that names a
later entry of the group scrolls down to it, like sense navigation.
The search field offers one suggestion for the whole group, without
numbers.

The build resolves each group up front, and the entry files carry
it as `homographs`. Dictionary.app already stacks the entries that
share a headword,
so the Apple export does not change.

## The sense index

A long entry hides its later senses below the fold. When a page has
more than one sense, the web app adds an index of links that scroll to
them. Each entry heads its own list in the index, as the way back up
to its headword and inflected forms.

On a wide viewport the index sits beside the page, scrolls along, and
pins to the top of the viewport. On a narrow viewport it folds into a
disclosure that the headword and its forms wrap around. The senses
start below it, clear of the fold marks at the right edge of their
meaning lines. An open disclosure therefore pushes the senses down and
does not cover them.

Dictionary.app gets the same pair, switched by the width of its view.
Its panel scrolls with the entry and does not pin. It runs no scripts,
so the marking below stays web-only there.

The index and the crimson margin mark show the sense that the
reader is on. A reading line a quarter down the viewport selects
this sense. The eyes of the reader rest at this position on the
screen. The last sense that moved up past the line takes the mark.

The content at the end of the page cannot move up to the line,
because the page stops. Over the last screen height, the line
moves down the viewport instead. When the page reaches its end, the line
reaches the foot of the viewport. The last sense takes the mark
there.

This line is the only rule, and it keeps no record of the direction
of travel. When the reader scrolls up again, the senses take the
mark at the same positions. A click in the index moves the mark to
its target immediately.

## The preferences

The preferences over the view are the alphabetical order of relation
members, the dataset's own presentation, and the language of the
interface. On a wide viewport they sit in the desk column beside the
page. They sit under the sense index, across a small gap, and they
pin with it. They take the same small sheet as the index, with the
same band. On a narrow viewport they return to a row under the search
field, at the width where the index folds into its disclosure.

The sheet moves left when the desk column is on the page. The sheet
and the column then centre together as one block. The sheet slides
rather than jumps when the viewport crosses the width.

They render in both places, and the stylesheet shows the one that the
viewport has room for. The rule under the search field stays in both,
because it separates the field from the entry under it.

The colophon moves with them. On a wide viewport it sits at the foot
of the desk column, under the preferences, and it takes no box there.
On a narrow viewport it returns to the foot of the sheet. The index,
the preferences and the colophon are the three parts that the column
takes from the page.

The language of the dictionary content is not one of them. No reader
can change it, so it reads as a fact in the colophon, beside the
title, the URI and the counts of the resource.

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

The web app is light-only by choice. Dictionary.app adapts to dark
mode. The export's stylesheet pins `prefers-color-scheme` to light.
It derives the palette from `CanvasText`/`Canvas` mixes instead of a
dark media block, so the semantic colours follow the actual
appearance.

## Accessibility

The search field and its suggestions form an ARIA combobox: arrow keys
move the active option via `aria-activedescendant` while focus stays
in the field. Focus follows navigation, to the headword of the new
entry or back to the search field. The re-render removes the element
that held focus.

Each view has one `h1`, and a status line announces the result counts.
Information in hover tooltips also reaches assistive technology
through visually hidden markup. The document language comes from the
manifest of the dataset at run time. UI strings that stay English
carry a `lang="en"` marker.

## UI translations

Most of what the interface shows is dataset text, already in the
language of the resource. The views also prefer the dataset's
descriptions over technical tags where both exist, so a Danish
resource shows "substantiv" with "noun" in the tooltip. That leaves
the interface's own strings, about two dozen, written in English in the
source: the search placeholder, the front-matter keys, and the error
messages.

A plain gettext setup translates them. The English string is the
lookup key and the fallback. `{n}` carries a count. The whole table is
a map from English to the target language.

The project ships a Danish table as `i18n/da.po` and uses it when the
manifest says that the resource is Danish. A dataset can add or
override translations with a `ui` section in its config or a `ui.po`
file next to its data. The web app also has a dropdown that selects
among the bundled languages, and localStorage remembers the choice per
dataset. The `ui` table of the dataset applies only while the chosen
language is the language of the resource, because its strings are in
that language.

The po format was the choice because translation tools (Poedit,
Weblate) edit it directly. The pottery library that parses it runs
only on the JVM at build and export time. The web app receives the
tables at compile time.

`clojure -M:i18n` extracts the translatable strings from the source
and writes them to `i18n/template.pot`. If the template or the
bundled Danish file no longer matches the code, the tests fail.
Neither can go stale in silence. The extraction sees only string
literals (or a let-bound conditional over literals) in a `hiccup/tr`
alias or a tr/en call. That is why the views sometimes repeat a
string instead of an abstraction.

DMLex 1.0 has a single description field per inventory tag, so one
export cannot be bilingual. A resource instead exports once per
language.

The presentation config is not bound that way. Its operations are the
same in every language, and only the names differ, so a name can be
given per language and one config can ship with every export. The
interface resolves the names once, to the language the reader picked, and
the operations downstream read plain strings. The text of the dataset
still stays in the language of its export, so a reader who picks
another language gets its names around the dataset's own words. The `langCode` of each export decides the rest: the dataset
text, the UI language, and the chrome of the Apple bundle. (The
export generates CSS overrides for the two strings that its
stylesheet shows as content.)

## Display over cleverness

Where a clever reduction can mislead, the display shows the full form
instead. The affix shorthand refuses multiword headwords, stem
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
Three items are declined, with reasons: a skip link (only one control
precedes the main content), fingerprinted asset names ([deploy.md](deploy.md)
tells hosts to use `no-cache` instead), and JSON-LD (the data files
serve agents better). Server-side rendering, once declined for hash
routing, is now what the data build writes. Five items wait until the
site has a stable public URL: a custom 404 page, Open Graph tags, a
canonical link, a sitemap (which needs absolute URLs), and
`llms.txt`.

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
