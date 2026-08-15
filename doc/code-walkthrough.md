# Code walkthrough

Scope: the entire source tree, i.e. the three commits on `main` up to
`6779692` plus the uncommitted work on top of them: the loose-end fixes
(the search-index error path among them), the test suite, the Apple
Dictionary export, and the page layout and combobox accessibility work.
Written 2026-08-14, extended 2026-08-15.

## The short version

The project is three programs that never run at the same time, joined
by shared pure functions and a directory of JSON files.
[build.clj](../src/dk/cst/dmlex_viewer/build.clj) is a JVM Clojure
batch job: it reads one DMLex 1.0 JSON file, resolves every tag and
relation up front, and shards the result into `public/data/` as a
manifest, a search index, and one small file per entry.
[appledict.clj](../src/dk/cst/dmlex_viewer/appledict.clj) is a second
JVM batch job that renders the same resolved entries as an Apple
Dictionary source project.
[app.cljs](../src/dk/cst/dmlex_viewer/app.cljs) is a ClojureScript
frontend on Replicant (no React): one atom of state, one search field, a
hash router, and a tree of pure view functions that render whichever
entry file the browser has fetched. All the lexicographic intelligence
lives in the build; the two display surfaces only render what it
resolves, which is what keeps them dataset-agnostic.

## Reading order

1. The data build: `-main` → `build!` → `->env` → `->entry-file` →
   `relation-rows` and `affix`
2. The data files the build writes, which are the contract between the
   build and the frontend
3. The Apple Dictionary export: `-main` → `export!` → `->entry` →
   `hiccup->xml`
4. The frontend: `init` → `route!` → `app` → the entry view tree
5. The shell: `index.html`, the stylesheets, the build configs
6. The tests
7. Documentation and loose ends

## 1. The data build

Entry point: `-main` at
[build.clj:240-246](../src/dk/cst/dmlex_viewer/build.clj#L240), invoked
as `clojure -J-Xmx8g -M:build <dmlex.json> [<out-dir>]` via the `:build`
alias in [deps.edn:5](../deps.edn#L5). It hands straight off to `build!`
([build.clj:224-238](../src/dk/cst/dmlex_viewer/build.clj#L224)):

```clojure
(let [resource (json/read-str (slurp in) :key-fn keyword)
      env      (->env resource)
      entries  (:entries resource)]
  (println "Writing" (count entries) "entries into" out)
  (doseq [entry entries
          :let [{:keys [file] :as m} (->entry-file env entry)]]
    (write-json! (io/file out "entries" (str file ".json")) m))
  (write-json! (io/file out "index.json") (index-rows resource))
  (write-json! (io/file out "manifest.json") (manifest resource)))
```

The whole resource is read into memory once (hence the `-Xmx8g` in the
usage string), then each entry is resolved and written independently.

`->env` ([build.clj:173-195](../src/dk/cst/dmlex_viewer/build.clj#L173))
builds the lookup environment every entry is resolved against. DMLex
keeps its controlled vocabularies as top-level inventories (`labelTags`,
`partOfSpeechTags`, `inflectedFormTags`, and so on); `->env` indexes each
one by tag with `index-by`, so that later code can turn a bare tag like
`"sb."` into its description in one map lookup. It also precomputes two
maps for relation resolution: `sense-home`, mapping every sense id to the
headword, file and indicator of the entry that owns it, and
`entry-home`, the same for entry ids. Their composition
`(some-fn sense-home entry-home)` becomes `:resolve-ref`, the function
that turns any member ref into a linkable display map. Finally
`:ref->idxs` comes from `member-refs`
([build.clj:96-102](../src/dk/cst/dmlex_viewer/build.clj#L96)), an
inverted index from each ref to the positions of the relations it
appears in, so that per-entry resolution does not rescan the full
relation list.

`->entry-file`
([build.clj:134-171](../src/dk/cst/dmlex_viewer/build.clj#L134)) is the
heart of the build: one DMLex entry in, one display-ready map out. Every
sub-object goes through a small resolver, and everything funnels through
`compact` ([build.clj:28-33](../src/dk/cst/dmlex_viewer/build.clj#L28))
so nils and empty collections never reach the JSON. The resolvers:

- `->label` ([build.clj:40-49](../src/dk/cst/dmlex_viewer/build.clj#L40))
  joins a label tag with its description, its type tag, the type's own
  description, and the first `sameAs` URI.
- `->inflected-form`
  ([build.clj:78-86](../src/dk/cst/dmlex_viewer/build.clj#L78)) adds the
  form-tag description and a computed `:short` affix.
- `->example`
  ([build.clj:88-94](../src/dk/cst/dmlex_viewer/build.clj#L88)) resolves
  the source identity of a citation.

`affix` ([build.clj:51-76](../src/dk/cst/dmlex_viewer/build.clj#L51)) is
the one piece of genuine string cleverness. It compresses an inflected
form to dictionary shorthand: the suffix after the longest common prefix
with the headword (`mennesket` → `-t`), or prefix notation when the form
instead shares its ending. The guards are the interesting part: it bails
to nil for multiword headwords, stem changes (the prefix must cover at
least half the headword, the shared suffix two thirds), remainders with
spaces, remainders without letters (which covers a form identical to
the headword), and remainders ending in a hyphen (compound stems like
*års-*), on the theory that a misleading abbreviation is worse than
none. When it returns nil both display surfaces fall back to the full
form text.

`relation-rows`
([build.clj:104-132](../src/dk/cst/dmlex_viewer/build.clj#L104)) is the
hardest function in the repo, and its docstring carries most of the
weight. DMLex relations are free-standing objects whose members point at
entries or senses by ref; the viewer instead wants each relation
attached to the entries it mentions. For a given ref, the function walks
the relations it belongs to (via `ref->idxs`), and for each one collects
the *other* members as display rows. The subtlety is the multi-role
case:

```clojure
own    (into #{} (comp (filter #(= ref (:ref %)))
                       (map :role))
             members)
multi? (> (count (distinct (map :role members))) 1)
others (cond->> (remove #(= ref (:ref %)) members)
         multi? (remove (comp own :role)))
```

In a relation with several roles (say hypernym/hyponym), members sharing
this ref's role are co-members rather than relata, so they are dropped;
in a single-role relation (plain synonymy) everyone else is kept. Rows
are then grouped by `[type own-roles member-role]` and merged, so five
separate hyponym relations seen from the shared hypernym become one row
with five members. Unresolvable refs are silently skipped by the
`:when target` clause.

Two smaller producers finish the job. `index-rows`
([build.clj:197-206](../src/dk/cst/dmlex_viewer/build.clj#L197)) emits
the search index as positional arrays `[headword file pos hom]`, sorted
with a `java.text.Collator` for the resource's `langCode`, so Danish `å`
sorts after `ø` without the frontend knowing anything about Danish.
`manifest` ([build.clj:208-216](../src/dk/cst/dmlex_viewer/build.clj#L208))
records the title, URI, language and the entry/sense/relation counts for
the colophon.

File naming is centralised in `->file`
([build.clj:19-26](../src/dk/cst/dmlex_viewer/build.clj#L19)): a DMLex
id that is already filename-safe is used as-is; anything else keeps its
safe characters and gains a hash suffix to stay unique. The frontend
never recomputes this; it only ever sees `:file` values the build wrote.

## 2. The data files

The contract between the build and the frontend, all under
`public/data/` (the Apple Dictionary export reads the DMLex JSON
directly and needs none of these):

- `manifest.json`: `{title, uri, langCode, entries, senses, relations}`.
- `index.json`: an array of `[headword file pos hom]` rows, pre-sorted.
  `pos` is the raw tags joined with `", "`, done at build time
  ([build.clj:204](../src/dk/cst/dmlex_viewer/build.clj#L204)).
- `entries/<file>.json`: the fully resolved entry. Senses carry their own
  `:relations` rows; the entry carries entry-level rows. Every member of
  every row already holds `{headword, file, indicator}`, so rendering a
  relation never needs another fetch.

## 3. The Apple Dictionary export

Entry point: `-main` at
[appledict.clj:390-398](../src/dk/cst/dmlex_viewer/appledict.clj#L390),
invoked as `clojure -J-Xmx8g -M:appledict <dmlex.json> [<out-dir>]
[<ddk-dir>]` via the `:appledict` alias. `export!`
([appledict.clj:374-388](../src/dk/cst/dmlex_viewer/appledict.clj#L374))
reads the DMLex JSON and writes a Dictionary Development Kit source
project into the output directory (default `export/appledict/`):
`Dictionary.xml`, `Dictionary.css`, `Info.plist` and a `Makefile`
pointing at the DDK. Building the final `.dictionary` bundle happens
outside this repo with `make && make install`.

The XML emitter is `hiccup->xml`
([appledict.clj:45-72](../src/dk/cst/dmlex_viewer/appledict.clj#L45)),
a small string renderer ported from the DanNet MVP: a namespaced
keyword like `:d/entry` becomes a `d:entry` element, nil children
vanish, childless elements self-close. It exists because the DDK's
mixed-namespace XHTML defeats clojure.data.xml's namespace-aware
emission.

`write-xml!`
([appledict.clj:291-303](../src/dk/cst/dmlex_viewer/appledict.clj#L291))
streams one `d:entry` per line, rendering
`(build/->entry-file env entry)` — the exact resolved maps the web
viewer displays. The view functions deliberately mirror app.cljs name
for name (`tagged`, `labels-view`, `members-dd`, `relations-view`,
`example-view`, `sense-view`, `inflections-view`, `paradigm-view`), so
the two surfaces render the same content: affixed short forms on the
inflections line (deduplicated via `shared/distinct-by` from
[shared.cljc](../src/dk/cst/dmlex_viewer/shared.cljc), the one
namespace both platforms load), the full paradigm behind an "all
forms" disclosure, relation members as `x-dictionary:r:<file>` links.
Two Dictionary.app-specific additions: `->index`
([appledict.clj:193-200](../src/dk/cst/dmlex_viewer/appledict.clj#L193))
emits a `d:index` term for the headword and every distinct full
inflected form, so lookups on inflected forms keep working, and
`d:priority="2"` marks everything but the headword, pos and
definitions as secondary, which the small Look Up panel omits.

The bundle identity comes from `bundle-info`
([appledict.clj:241-260](../src/dk/cst/dmlex_viewer/appledict.clj#L241)):
the resource's own `title`/`uri`/`langCode`, overridden by a Dublin
Core `metadata.json` found next to the input file — the file DanNet's
DMLex export ships for exactly this purpose. The same map feeds
`info-plist` and the generic `front-matter` entry. The stylesheet is a
concatenation of the shared
[tokens.css](../public/css/tokens.css) and the Dictionary.app rules in
[resources/appledict/style.css](../resources/appledict/style.css); see
[appledict-export.md](appledict-export.md) for the design rationale.

## 4. The frontend

Entry point: `init` at
[app.cljs:399-412](../src/dk/cst/dmlex_viewer/app.cljs#L399), wired in
as the `:init-fn` in [shadow-cljs.edn:5](../shadow-cljs.edn#L5):

```clojure
(add-watch state ::render (fn [_ _ _ _] (render!)))
(fetch-json! "data/manifest.json"
             (fn [{:keys [langCode] :as manifest}]
               (swap! state assoc :manifest manifest)
               (when langCode
                 (set! (.-lang js/document.documentElement) langCode))
               (update-title!)))
(load-index!)
(.addEventListener js/window "hashchange" route!)
(route!)
(render!))
```

The architecture is the smallest possible: a single `defonce` atom
([app.cljs:12-19](../src/dk/cst/dmlex_viewer/app.cljs#L12)) holding
`{:manifest :index :index-error :query :active :entry :error}`, a watch
that re-renders the whole app on any change, and Replicant diffing the
resulting hiccup into the DOM. Note the manifest callback setting `documentElement.lang` at
run time: the static `index.html` says `lang="en"`, and the dataset's
own language takes over once the manifest arrives, which is how the
viewer stays language-agnostic (the audit record makes a point of this).

`load-index!` ([app.cljs:39-55](../src/dk/cst/dmlex_viewer/app.cljs#L39))
turns the positional index rows into maps and caches a lowercased
headword per row, so that `matches`
([app.cljs:57-63](../src/dk/cst/dmlex_viewer/app.cljs#L57)) is a plain
prefix filter capped at the first 100 hits with a transducer, no
per-keystroke lowercasing of the whole index. A failed
index fetch lands in its own `:index-error` slot rather than the shared
`:error`, so that `route!` clearing `:error` on a successful entry load
cannot swallow it.

Routing is a regex over the URL fragment. `route!`
([app.cljs:119-140](../src/dk/cst/dmlex_viewer/app.cljs#L119)) matches
`#/entry/<file>`, fetches `data/entries/<file>.json` into `:entry`, sets
the document title and scrolls to the top; no match (or an explicit
`#/`) clears the entry, which is the front page. A failed fetch clears
`:entry` and stores the message in `:error`. Focus follows the
navigation: the headword (`tabindex -1`) takes it on an entry, the
search field takes it on the front page, so a keyboard or screen-reader
user is never left on an element the re-render removed.

The root view `app`
([app.cljs:351-392](../src/dk/cst/dmlex_viewer/app.cljs#L351)) puts the
priority order of the UI in one `cond`: a non-blank query shows the
search view regardless of what entry is loaded; otherwise the loaded
entry; otherwise an error page; otherwise the intro text. `app` computes
the result rows once per render and threads them into both the input's
ARIA attributes and the view below.

The search field and the result list form an ARIA combobox: the input
carries `role="combobox"`, `aria-expanded`, `aria-controls` and
`aria-activedescendant`, and DOM focus never leaves it while arrow keys
move an *active* row. `search-keydown!`
([app.cljs:91-108](../src/dk/cst/dmlex_viewer/app.cljs#L91)) handles the
keys: the arrows move the active row via the pure `next-active`
([app.cljs:71-81](../src/dk/cst/dmlex_viewer/app.cljs#L71)) — Down
enters the list at the top, Up leaves it there — with `set-active!`
scrolling the row into view; Enter follows the active row (or the first,
so Enter-without-arrows still jumps to the top match); Enter on a blank
field goes home; Escape clears the search. Both navigation paths clear
`:query` and `:active` (`goto-entry!` at
[app.cljs:65-69](../src/dk/cst/dmlex_viewer/app.cljs#L65) for the
keyboard, an inline click handler at
[app.cljs:320](../src/dk/cst/dmlex_viewer/app.cljs#L320) for clicked
results, where the `href` does the actual navigation), which is what
flips the `cond` from results back to the entry.

`search-view`
([app.cljs:327-336](../src/dk/cst/dmlex_viewer/app.cljs#L327)) sits
between the `cond` and the results: with rows in hand it delegates to
`results-view`; when the index failed to load it shows an error
paragraph asking for a reload; while the index is still loading it shows
nothing. `results-view`
([app.cljs:298-325](../src/dk/cst/dmlex_viewer/app.cljs#L298)) renders
the hit list as the combobox's listbox — `role="listbox"` on the list,
each link a `role="option"` with a stable id for
`aria-activedescendant`, the active one marked `aria-selected` and
highlighted — with the matched prefix in `<mark>` (via
`result-headword`), plus a `role="status"` line announcing the count;
the line is visually hidden while there are hits and becomes the visible
"No matches" message when there are none.

`entry-view` ([app.cljs:268-287](../src/dk/cst/dmlex_viewer/app.cljs#L268))
is the top of the display tree and mirrors the shape of the entry file:

- The header: headword with homograph superscript, parts of speech,
  `inflections-view`, `paradigm-view`, entry-level `labels-view`.
- `inflections-view`
  ([app.cljs:229-248](../src/dk/cst/dmlex_viewer/app.cljs#L229)): the
  run-in line of short forms, deduplicated with `distinct-by` on the
  short form so *-en* appears once even when two paradigm slots share
  it, and with forms spelled like the headword left out (the plural of
  *år* is *år*; the line is for different representations). The
  paradigm slot stays in a visually hidden `<dt>` for assistive tech
  and doubles as the mouse tooltip.
- `paradigm-view`
  ([app.cljs:250-266](../src/dk/cst/dmlex_viewer/app.cljs#L250)): the
  same forms again, un-deduplicated and in full, as a table behind an
  "all forms" disclosure. The two views are deliberately redundant: one
  optimised for scanning, one for completeness.
- The senses as a numbered list, `sense-view`
  ([app.cljs:217-227](../src/dk/cst/dmlex_viewer/app.cljs#L217)):
  indicator, definitions joined with `";"`, examples as `<blockquote>`
  with `<cite>` sources, sense labels, sense relations. The CSS drops
  the numbering when there is only one sense (the `single` class at
  [app.cljs:285](../src/dk/cst/dmlex_viewer/app.cljs#L285)).
- `relations-view`
  ([app.cljs:190-203](../src/dk/cst/dmlex_viewer/app.cljs#L190)): the
  pre-resolved rows as a `<nav aria-label="related">` definition list;
  `members-dd` ([app.cljs:178-188](../src/dk/cst/dmlex_viewer/app.cljs#L178))
  folds a row with more than ten members behind a "N entries"
  disclosure.

Two tiny helpers carry the semantics through the whole tree: `tagged`
([app.cljs:142-148](../src/dk/cst/dmlex_viewer/app.cljs#L142)) renders
any tag from a controlled inventory as `<abbr title=…>` when the build
supplied a description, and plain text when it did not; `labels-view`
([app.cljs:157-171](../src/dk/cst/dmlex_viewer/app.cljs#L157)) groups
labels by type with `partition-by` into the aligned key/value layout.
`footer-view` ([app.cljs:338-349](../src/dk/cst/dmlex_viewer/app.cljs#L338))
closes every page with the colophon: resource title, link and the counts
from the manifest.

## 5. The shell

[index.html](../public/index.html) is thirteen lines of head plus an
empty `#app` div and a `<noscript>` fallback; everything else is
rendered. The visual identity — an Old Style serif stack in black,
white and grey with one crimson accent — is named in the custom
properties of [tokens.css](../public/css/tokens.css), which both the
web stylesheet and the Apple Dictionary export consume; editing a
token restyles both outputs. [style.css](../public/css/style.css)
holds the web rules. Three ideas are worth knowing when editing it.
First, the page-on-a-desk layout
([style.css:22-44](../public/css/style.css#L22)): the body is a flat
grey desk and `.container` is a white, viewport-filling page with a
hairline edge and a crimson top band; under 48rem the desk disappears
and the page fills the viewport, keeping only the band. Second, a
shared "small-caps metadata voice"
([style.css:69-75](../public/css/style.css#L69)) styles every key-like
thing (label keys, relation roles, stat names, citations) identically.
Third, labels and relations share one aligned key/value layout
([style.css:162-186](../public/css/style.css#L162)): the `<dt>` keys are
absolutely positioned into a fixed right-aligned column left of the
values, collapsing to stacked rows under 40rem.

The build configs are minimal: [deps.edn](../deps.edn) defines the
`:build` and `:appledict` aliases for the two JVM jobs, a `:test`
alias for the JVM tests, and a `:shadow` alias pulling shadow-cljs and
Replicant; [shadow-cljs.edn](../shadow-cljs.edn) defines the `:app`
browser build with `dev-http` serving `public/` on port 8000, plus a
`:test` node-test build. [package.json](../package.json) exists only
to pin shadow-cljs for npm.

## 6. The tests

The clever logic all lives in pure functions, and the tests pin exactly
that. [build_test.clj](../test/dk/cst/dmlex_viewer/build_test.clj)
(`clojure -M:test`) covers the filename hashing of `->file`, the guard
rails of `affix` (suffix, prefix notation, and each of the four bail-out
cases), the co-member exclusion and row merging of `relation-rows`, the
Danish collation of `index-rows`, and one end-to-end `->entry-file`
resolution over a two-entry resource.
[app_test.cljs](../test/dk/cst/dmlex_viewer/app_test.cljs)
(`npx shadow-cljs compile test && node out/node-tests.js`) covers the
prefix filter and limit arity of `matches`, the shared `distinct-by`,
`result-headword` marking, the three states of `search-view` (loaded,
failed, loading), the headword filter of `inflections-view`, the
arrow-key arithmetic of `next-active`, and the listbox/option markup of
`results-view`. The views being plain data means these are asserted
directly on the hiccup.
[appledict_test.clj](../test/dk/cst/dmlex_viewer/appledict_test.clj)
(run with the JVM tests) pins the XML emitter (escaping, self-closing,
the `d:` prefix), the index-term derivation, the bundle-info merge and
plist escaping, and one full entry rendering over the shared fixture:
the affixed short form, the paradigm disclosure, the `x-dictionary`
relation link and the resolved label URI.

## 7. Documentation and loose ends

The [README](../README.md) doubles as the deploy runbook: it delegates
HTTPS, compression and five security/caching headers to the host, on the
argument that a no-third-party static site can carry a strict
`default-src 'self'` CSP. [doc/website-spec.md](website-spec.md) is the
audit record against The Website Specification, dated 2026-08-14, and is
mostly a record of deliberate omissions with reasons (no sitemap because
hash routing gives one URL, no JSON-LD because the data files serve
agents better, and so on).

One loose end remains, and it is the next feature rather than a defect:
the README TODO sketches an optional per-dataset presentation config to
hide, rename and reorder label types without the viewer learning what
any label type means. The analysis and implementation plan for it is in
[presentation-config.md](presentation-config.md).

Earlier loose ends, since resolved: the Enter key recomputed the full
100-row match list (now the root view computes the rows once per
render and hands them to the keydown handler), an index-load
failure was invisible while an entry was on screen (now `:index-error`
and `search-view`), the `comment` block in build.clj pointed at a
DanNet path outside the repo (now the generic `datasets/` example), and
nothing was tested (now section 6). The Apple Dictionary conversion,
analysed in [appledict-export.md](appledict-export.md) as a migration
from DanNet's MVP, is implemented as section 3 describes; what remains
of that plan is its config-dependent step (the presentation ops and
the DanNet taste file) and deleting the superseded DanNet namespace.
