# dmlex-viewer

<img src="web-viewer.png" align="right" width="340"
     alt="The viewer shows the entry for the Danish word æblesort (apple
          cultivar) as a white page on a grey background: the search field,
          the headword, the affixed inflected forms, a definition, the
          classification, and a panel of related words.">

A generic viewer for [DMLex 1.0](https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/dmlex-v1.0-os.html)
lexicographic resources. The viewer shows a DMLex file as a dictionary.
It has one search field, hyperlink navigation, and a typography-first
entry display in black, white and grey.

The viewer is a static site with no server and no database. A build step
shards the single-file DMLex JSON serialization into small data files.
The browser fetches only the entry that it shows.

The project began as a side project of DanNet. It works on any DMLex 1.0
JSON file and holds no DanNet knowledge. The frontend uses ClojureScript
and [Replicant](https://github.com/cjohansen/replicant), without React.

## Build the data

1. Copy your DMLex JSON file, or a zip export containing it, into
   `datasets/`.
2. Run the build:

```sh
clojure -J-Xmx8g -M:build datasets/your-dmlex.json
```

A zip works as-is: the build finds the DMLex JSON inside it and reads
the companion files from the same place, so a downloaded export like
`dannet-dmlex.zip` needs no unpacking.

The build writes three kinds of file into `public/data/`:

- `manifest.json` holds the resource metadata. A Dublin Core
  `metadata.json` next to the DMLex file merges in; the viewer shows
  its description, rights, license and sources on the front page.
- `index.json` holds the search index, sorted with the collation of the
  resource language.
- `entries/<id>.json` holds one pre-resolved file for each entry.

The build resolves the display data before the frontend runs. Labels,
label types, parts of speech, relation types, and example sources carry
the description and the `sameAs` URI of their inventory tag; the viewer
renders the URI as a link. Inflected forms carry the description of
their `inflectedFormTag` and a computed affix, for example `-t` for the
form *mennesket*. Definition and example texts carry their stand-off
`headwordMarkers` and `collocateMarkers` as display runs; the viewer
renders the marked headword in bold and a collocate with its lemma as
the tooltip. The labels of an example trail it in parentheses, and the
`headwordTranslations` of a sense render as a language-grouped line of
equivalents. Each
relation attaches to its member entries and senses as display rows.
Rows with the same relation type and the same direction merge into one
row; a row prefers the description of its relation instance, then of
its role's memberType, then of its relation type as the tooltip, and a
member whose memberType hints `"none"` stays out.

The members of a row keep the listing order of the dataset. A
`presentation.json` with `"memberOrder": "collation"` sorts them by the
`obverseListingOrder` of each member first, then by the headword in the
collation of the resource language; a member without an order sorts
after every member with one. The web viewer adds a checkbox switching
between the two orders. DanNet, for example, derives the member order
from how many relations point at each synset, so with `"collation"` the
most central words come first.

## Present the data

A dataset can ship its taste as a small `presentation.json` file next
to its DMLex JSON. The file can hide, rename, and reorder the label
types and the relation types, and it can rename the relation roles.
The keys are the dataset's own tags, so the viewer applies the
operations without learning what any tag means. Without the file,
everything renders with the dataset's own names and order.

```jsonc
{
  "labelTypes": {
    "order":    ["domain", "register"],
    "unlisted": "hide",              // or "after" (the default)
    "rename":   {"domain": "emne"},
    "combine":  {"sentiment": "sentimentValue"},
    "show":     {"synset": "description"}
  },
  "relationTypes": {"order": ["synonym"]},
  "roles":         {"rename": {"hypernym": "overbegreb"}},
  "memberOrder":   "collation",      // or "listing" (the default)
  "linkResolver":  "https://wordnet.dk/dannet/external?subject=",

  // Translations of the viewer chrome into the dataset's language.
  "ui": {
    "all forms":   "alle former",
    "{n} entries": "{n} ord"
  },

  // Only the Apple dictionary export reads this section.
  "appledict": {
    "identifier":  "org.example.dictionary",
    "css":         "appledict-extra.css",
    "frontMatter": "front-matter.html"
  }
}
```

The viewer's own interface strings are English. The `ui` section
translates them in the gettext style: the English string is its own
key, `{n}` carries a count, and singular and plural are separate keys
(`"1 match shown"`, `"{n} matches shown"`). An untranslated string
stays English and keeps its `lang="en"` marker for assistive
technology; a translated one is assumed to be in the language of the
resource. The Apple dictionary export applies the same table at export
time, including the strings its stylesheet renders as CSS content.

The viewer bundles a Danish translation ([i18n/da.po](i18n/da.po)) and
picks it by the `langCode` of the resource; adding a language is one
more po file in [i18n/](i18n/) and its entry in
`dk.cst.dmlex-viewer.translations`. The web viewer adds a dropdown for
switching the UI language, remembered per dataset in the browser. A dataset can override or extend
the bundled table with its own `ui` section, or ship the translations
as a gettext `ui.po` next to its DMLex file — the format translation
tools like Poedit produce — which the builds merge over the section.
The keys live in [i18n/template.pot](i18n/template.pot), regenerated
from the source with `clojure -M:i18n`; tests compare the template and
every bundled po file against a fresh extraction, so neither can drift
from the code.

Three rules: `hide` always wins, `order` lists first and `unlisted`
decides the rest, and `rename` changes only the displayed name. Label
types can also `combine` a qualifier type into its host and `show` the
description in place of the tag; relation types can gather their rows
into titled sections with `groups`. A `linkResolver` reroutes every
`sameAs` link through the dataset's own resource browser — vocabulary
URIs usually serve raw RDF files — while links already on the
resolver's host stay direct. The
data build carries the file into `public/data/` (merging any `ui.po`
translations), and the Apple dictionary export reads it next to its
input file. The design decisions behind the config are in
[doc/design.md](doc/design.md).

## Build the frontend

1. Install the npm dependencies: `npm install`
2. Compile the release build: `npx shadow-cljs release app`

## Serve

Point a static file server at `public/`:

```sh
python3 -m http.server 8000 -d public
```

## Deploy

The `public/` directory is a complete static site. Any static host can
serve it.

For a production host:

1. Serve every file over HTTPS. Redirect plain HTTP to HTTPS.
2. Compress text responses with brotli or gzip.
3. Set these response headers:

| Header | Value |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| `X-Content-Type-Options` | `nosniff` |
| `Content-Security-Policy` | `default-src 'self'` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Cache-Control` | `no-cache` for `index.html`, `js/main.js` and `data/` |

The viewer loads no third-party resources, so the strict policy is safe.
The file names do not change between builds, so `no-cache` makes the
browser revalidate each file.

Some files stay out of the repository until the site has a stable public
URL: a custom 404 page, the Open Graph tags, a `rel="canonical"` link,
and an `llms.txt` for AI agents. The reasoning, and the record of the
audit against [The Website Specification](https://specification.website/),
is in [doc/design.md](doc/design.md).

## Build an Apple dictionary

<img src="apple-dictionary.png" align="right" width="340"
     alt="Dictionary.app shows the DanNet entry for the Danish word æblesort
          (apple cultivar): the headword, the affixed inflected forms, a
          definition, the classification, and a panel of related words. The
          sidebar lists the inflected forms as search results.">

The same DMLex file can become a dictionary for the macOS Dictionary
app. Run the export from the project root:

```sh
clojure -J-Xmx8g -M:appledict datasets/your-dmlex.json
```

The export writes an Apple Dictionary source project into
`export/appledict/`: the dictionary XML, the stylesheet, an Info.plist
and a Makefile. The entries show the same content as the web viewer,
with the short inflected forms on the entry and the full forms in the
search index. If a Dublin Core `metadata.json` sits next to the DMLex
file, its fields fill the bundle metadata and the front matter. A zip
export works here too, exactly as in the data build.

To build and install the `.dictionary` bundle, you need the Dictionary
Development Kit from Apple's "Additional Tools for Xcode". Then:

```sh
cd export/appledict && make && make install
```

The export command takes two optional arguments: the output directory
and the path of the Dictionary Development Kit. The Makefile points at
`/Library/Developer/Extras/Dictionary Development Kit` by default, and
each export writes a new Makefile. If your kit is elsewhere, give its
path on each export:

```sh
clojure -J-Xmx8g -M:appledict datasets/your-dmlex.json export/appledict "$HOME/Developer/Dictionary Development Kit"
```

## Develop and test

Start the development watch:

```sh
npx shadow-cljs watch app
```

The watch compiles on each change and serves `public/` at
<http://localhost:8000>.

The resolution logic of the data build has JVM tests:

```sh
clojure -M:test
```

The search and view logic of the frontend has Node tests:

```sh
npx shadow-cljs compile test && node out/node-tests.js
```

