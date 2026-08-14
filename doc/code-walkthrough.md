# Code walkthrough

Scope: the entire source tree, i.e. the three commits on `main` up to
`6779692` plus the uncommitted loose-end fixes on top of them (the
search-index error path, the `matches` limit arity, and the test suite).
Written 2026-08-14.

## The short version

The project is two programs that never run at the same time, joined by a
directory of JSON files. [build.clj](../src/dk/cst/dmlex_viewer/build.clj)
is a JVM Clojure batch job: it reads one DMLex 1.0 JSON file, resolves
every tag and relation up front, and shards the result into
`public/data/` as a manifest, a search index, and one small file per
entry. [app.cljs](../src/dk/cst/dmlex_viewer/app.cljs) is a ClojureScript
frontend on Replicant (no React): one atom of state, one search field, a
hash router, and a tree of pure view functions that render whichever
entry file the browser has fetched. All the lexicographic intelligence
lives in the build; the frontend only displays what it is given, which is
what keeps it dataset-agnostic.

## Reading order

1. The data build: `-main` → `build!` → `->env` → `->entry-file` →
   `relation-rows` and `affix`
2. The data files the build writes, which are the contract between the
   two halves
3. The frontend: `init` → `route!` → `app` → the entry view tree
4. The shell: `index.html`, the stylesheet, the build configs
5. The tests
6. Documentation and loose ends

## 1. The data build

Entry point: `-main` at
[build.clj:237-243](../src/dk/cst/dmlex_viewer/build.clj#L237), invoked
as `clojure -J-Xmx8g -M:build <dmlex.json> [<out-dir>]` via the `:build`
alias in [deps.edn:5](../deps.edn#L5). It hands straight off to `build!`
([build.clj:221-235](../src/dk/cst/dmlex_viewer/build.clj#L221)):

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

`->env` ([build.clj:170-192](../src/dk/cst/dmlex_viewer/build.clj#L170))
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
([build.clj:93-99](../src/dk/cst/dmlex_viewer/build.clj#L93)), an
inverted index from each ref to the positions of the relations it
appears in, so that per-entry resolution does not rescan the full
relation list.

`->entry-file`
([build.clj:131-168](../src/dk/cst/dmlex_viewer/build.clj#L131)) is the
heart of the build: one DMLex entry in, one display-ready map out. Every
sub-object goes through a small resolver, and everything funnels through
`compact` ([build.clj:26-31](../src/dk/cst/dmlex_viewer/build.clj#L26))
so nils and empty collections never reach the JSON. The resolvers:

- `->label` ([build.clj:38-47](../src/dk/cst/dmlex_viewer/build.clj#L38))
  joins a label tag with its description, its type tag, the type's own
  description, and the first `sameAs` URI.
- `->inflected-form`
  ([build.clj:75-83](../src/dk/cst/dmlex_viewer/build.clj#L75)) adds the
  form-tag description and a computed `:short` affix.
- `->example`
  ([build.clj:85-91](../src/dk/cst/dmlex_viewer/build.clj#L85)) resolves
  the source identity of a citation.

`affix` ([build.clj:49-73](../src/dk/cst/dmlex_viewer/build.clj#L49)) is
the one piece of genuine string cleverness. It compresses an inflected
form to dictionary shorthand: the suffix after the longest common prefix
with the headword (`mennesket` → `-t`), or prefix notation when the form
instead shares its ending. The guards are the interesting part: it bails
to nil for multiword headwords, stem changes (the prefix must cover at
least half the headword, the shared suffix two thirds), remainders with
spaces, and remainders without letters, on the theory that a misleading
abbreviation is worse than none. When it returns nil the frontend falls
back to the full form text.

`relation-rows`
([build.clj:101-129](../src/dk/cst/dmlex_viewer/build.clj#L101)) is the
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
([build.clj:194-203](../src/dk/cst/dmlex_viewer/build.clj#L194)) emits
the search index as positional arrays `[headword file pos hom]`, sorted
with a `java.text.Collator` for the resource's `langCode`, so Danish `å`
sorts after `ø` without the frontend knowing anything about Danish.
`manifest` ([build.clj:205-213](../src/dk/cst/dmlex_viewer/build.clj#L205))
records the title, URI, language and the entry/sense/relation counts for
the colophon.

File naming is centralised in `->file`
([build.clj:17-24](../src/dk/cst/dmlex_viewer/build.clj#L17)): a DMLex
id that is already filename-safe is used as-is; anything else keeps its
safe characters and gains a hash suffix to stay unique. The frontend
never recomputes this; it only ever sees `:file` values the build wrote.

## 2. The data files

The contract between the two programs, all under `public/data/`:

- `manifest.json`: `{title, uri, langCode, entries, senses, relations}`.
- `index.json`: an array of `[headword file pos hom]` rows, pre-sorted.
  `pos` is the raw tags joined with `", "`, done at build time
  ([build.clj:201](../src/dk/cst/dmlex_viewer/build.clj#L201)).
- `entries/<file>.json`: the fully resolved entry. Senses carry their own
  `:relations` rows; the entry carries entry-level rows. Every member of
  every row already holds `{headword, file, indicator}`, so rendering a
  relation never needs another fetch.

## 3. The frontend

Entry point: `init` at
[app.cljs:351-364](../src/dk/cst/dmlex_viewer/app.cljs#L351), wired in
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
([app.cljs:11-17](../src/dk/cst/dmlex_viewer/app.cljs#L11)) holding
`{:manifest :index :index-error :query :entry :error}`, a watch that re-renders the
whole app on any change, and Replicant diffing the resulting hiccup into
the DOM. Note the manifest callback setting `documentElement.lang` at
run time: the static `index.html` says `lang="en"`, and the dataset's
own language takes over once the manifest arrives, which is how the
viewer stays language-agnostic (the audit record makes a point of this).

`load-index!` ([app.cljs:37-53](../src/dk/cst/dmlex_viewer/app.cljs#L37))
turns the positional index rows into maps and caches a lowercased
headword per row, so that `matches`
([app.cljs:55-64](../src/dk/cst/dmlex_viewer/app.cljs#L55)) is a plain
prefix filter capped at the first `n` hits (100 by default) with a
transducer, no per-keystroke lowercasing of the whole index. A failed
index fetch lands in its own `:index-error` slot rather than the shared
`:error`, so that `route!` clearing `:error` on a successful entry load
cannot swallow it.

Routing is a regex over the URL fragment. `route!`
([app.cljs:93-106](../src/dk/cst/dmlex_viewer/app.cljs#L93)) matches
`#/entry/<file>`, fetches `data/entries/<file>.json` into `:entry`, sets
the document title and scrolls to the top; no match (or an explicit
`#/`) clears the entry, which is the front page. A failed fetch clears
`:entry` and stores the message in `:error`.

The root view `app`
([app.cljs:310-344](../src/dk/cst/dmlex_viewer/app.cljs#L310)) puts the
priority order of the UI in one `cond`: a non-blank query shows the
search view regardless of what entry is loaded; otherwise the loaded
entry; otherwise an error page; otherwise the intro text. The search
field's handlers
([app.cljs:323-331](../src/dk/cst/dmlex_viewer/app.cljs#L323)) are the
only interesting event code:

```clojure
:keydown (fn [e]
           (when (= "Enter" (.-key e))
             (if (str/blank? query)
               (set! (.-hash js/location) "")
               (when-let [row (first (matches index query 1))]
                 (goto-entry! (:file row))))))
```

Enter jumps to the top match, computed with the limit arity so the
keydown does not rebuild the 100-row list the render already builds;
Enter on a blank field goes home. The blank branch is the whole of the
latest commit ("fix empty search bar behaviour") — before it, Enter in
an empty field tried to navigate to the first match of the empty prefix,
i.e. an arbitrary entry. Both navigation paths clear `:query`
(`goto-entry!` at
[app.cljs:78-82](../src/dk/cst/dmlex_viewer/app.cljs#L78) for Enter, an
inline click handler at
[app.cljs:282](../src/dk/cst/dmlex_viewer/app.cljs#L282) for clicked
results, where the `href` does the actual navigation), which is what
flips the `cond` from results back to the entry.

`search-view`
([app.cljs:287-295](../src/dk/cst/dmlex_viewer/app.cljs#L287)) sits
between the `cond` and the results: with the index loaded it delegates
to `results-view`; when the index failed to load it shows an error
paragraph asking for a reload; while the index is still loading it shows
nothing. `results-view`
([app.cljs:266-285](../src/dk/cst/dmlex_viewer/app.cljs#L266)) renders
the hit list with the matched prefix in `<mark>` (via
`result-headword`), plus a `role="status"` line announcing the count;
the line is visually hidden while there are hits and becomes the visible
"No matches" message when there are none.

`entry-view` ([app.cljs:236-255](../src/dk/cst/dmlex_viewer/app.cljs#L236))
is the top of the display tree and mirrors the shape of the entry file:

- The header: headword with homograph superscript, parts of speech,
  `inflections-view`, `paradigm-view`, entry-level `labels-view`.
- `inflections-view`
  ([app.cljs:198-216](../src/dk/cst/dmlex_viewer/app.cljs#L198)): the
  run-in line of short forms, deduplicated with `distinct-by` on the
  short form so *-en* appears once even when two paradigm slots share
  it. The paradigm slot stays in a visually hidden `<dt>` for assistive
  tech and doubles as the mouse tooltip.
- `paradigm-view`
  ([app.cljs:218-234](../src/dk/cst/dmlex_viewer/app.cljs#L218)): the
  same forms again, un-deduplicated and in full, as a table behind an
  "all forms" disclosure. The two views are deliberately redundant: one
  optimised for scanning, one for completeness.
- The senses as a numbered list, `sense-view`
  ([app.cljs:186-196](../src/dk/cst/dmlex_viewer/app.cljs#L186)):
  indicator, definitions joined with `";"`, examples as `<blockquote>`
  with `<cite>` sources, sense labels, sense relations. The CSS drops
  the numbering when there is only one sense (the `single` class at
  [app.cljs:253](../src/dk/cst/dmlex_viewer/app.cljs#L253)).
- `relations-view`
  ([app.cljs:159-172](../src/dk/cst/dmlex_viewer/app.cljs#L159)): the
  pre-resolved rows as a `<nav aria-label="related">` definition list;
  `members-dd` ([app.cljs:147-157](../src/dk/cst/dmlex_viewer/app.cljs#L147))
  folds a row with more than ten members behind a "N entries"
  disclosure.

Two tiny helpers carry the semantics through the whole tree: `tagged`
([app.cljs:111-117](../src/dk/cst/dmlex_viewer/app.cljs#L111)) renders
any tag from a controlled inventory as `<abbr title=…>` when the build
supplied a description, and plain text when it did not; `labels-view`
([app.cljs:126-140](../src/dk/cst/dmlex_viewer/app.cljs#L126)) groups
labels by type with `partition-by` into the aligned key/value layout.
`footer-view` ([app.cljs:297-308](../src/dk/cst/dmlex_viewer/app.cljs#L297))
closes every page with the colophon: resource title, link and the counts
from the manifest.

## 4. The shell

[index.html](../public/index.html) is thirteen lines of head plus an
empty `#app` div and a `<noscript>` fallback; everything else is
rendered. [style.css](../public/css/style.css) carries the actual visual
identity: an Old Style serif stack in black, white and grey with one
crimson accent, all named in custom properties at
[style.css:4-18](../public/css/style.css#L4). Two ideas are worth
knowing when editing it. First, a shared "small-caps metadata voice"
([style.css:63-68](../public/css/style.css#L63)) styles every key-like
thing (label keys, relation roles, stat names, citations) identically.
Second, labels and relations share one aligned key/value layout
([style.css:155-179](../public/css/style.css#L155)): the `<dt>` keys are
absolutely positioned into a fixed right-aligned column left of the
values, collapsing to stacked rows under 40rem.

The build configs are minimal: [deps.edn](../deps.edn) defines the
`:build` alias for the JVM job, a `:test` alias for the JVM tests, and a
`:shadow` alias pulling shadow-cljs and Replicant;
[shadow-cljs.edn](../shadow-cljs.edn) defines the `:app` browser build
with `dev-http` serving `public/` on port 8000, plus a `:test`
node-test build. [package.json](../package.json) exists only to pin
shadow-cljs for npm.

## 5. The tests

The clever logic all lives in pure functions, and the tests pin exactly
that. [build_test.clj](../test/dk/cst/dmlex_viewer/build_test.clj)
(`clojure -M:test`) covers the filename hashing of `->file`, the guard
rails of `affix` (suffix, prefix notation, and each of the four bail-out
cases), the co-member exclusion and row merging of `relation-rows`, the
Danish collation of `index-rows`, and one end-to-end `->entry-file`
resolution over a two-entry resource.
[app_test.cljs](../test/dk/cst/dmlex_viewer/app_test.cljs)
(`npx shadow-cljs compile test && node out/node-tests.js`) covers the
prefix filter and limit arity of `matches`, `distinct-by`,
`result-headword` marking, and the three states of `search-view`
(loaded, failed, loading). The views being plain data means the failure
state is asserted directly on the hiccup.

## 6. Documentation and loose ends

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
100-row match list (now the limit arity of `matches`), an index-load
failure was invisible while an entry was on screen (now `:index-error`
and `search-view`), the `comment` block in build.clj pointed at a
DanNet path outside the repo (now the generic `datasets/` example), and
nothing was tested (now section 5).
