# DMLex browser

<img src="web-app.png" align="right" width="340"
     alt="The app shows the entry for the Danish word æblesort (apple
          cultivar) as a white page on a grey background: the search field,
          the headword, the affixed inflected forms, a definition, the
          classification, and a panel of related words.">

A generic browser for [DMLex 1.0](https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/dmlex-v1.0-os.html)
lexicographic resources. The app shows a DMLex file as a dictionary.
It has one search field, hyperlink navigation, and a typography-first
presentation using black, grey, and red for details.

The app is a static site with no server and no database. A build step
shards the single-file DMLex JSON serialization into small data files
and pre-renders one page per entry. Every page reads without
JavaScript. The app then takes it over, and the browser fetches only
the entry that it shows.

The project began as a side project of DanNet. It works on any DMLex 1.0
JSON file and holds no DanNet-specific knowledge.

A first run is three sections in this order: build the data, build the
frontend, and serve. An [Apple dictionary](#build-an-apple-dictionary)
is a separate, single-step export from the same DMLex file.

The build commands run on the Clojure CLI. The official
[install guide](https://clojure.org/guides/install_clojure) covers
macOS, Linux and Windows, and the Java that Clojure needs.

## Build the data

1. Copy your DMLex JSON file, or a zip export that contains it, into
   `datasets/`.
2. Run the build:

```sh
clojure -J-Xmx8g -M:build datasets/your-dmlex.json
```

The build finds the DMLex JSON inside a zip and reads the companion
files from the same place. A downloaded export like `dannet-dmlex.zip`
needs no unpacking.

The build writes three kinds of data file into `public/data/`:

- `manifest.json` holds the resource metadata. A Dublin Core
  `metadata.json` next to the DMLex file merges in. The app shows
  the description, rights, license and sources on the front page.
- `index.json` holds the search index, sorted with the collation of the
  resource language.
- `entries/<id>.json` holds one pre-resolved file for each entry.

The build also writes the pages of the site next to the data, through
the same views that the browser renders:

- `public/index.html` is the front page, titled with the resource and
  carrying its front matter.
- `public/entry/<id>/index.html` is the page of one homograph group.
  Its URL is the one the app navigates to. A reader without
  JavaScript, and a crawler, see the entry that a reader with
  JavaScript sees.

Both are generated, so neither is in the repository. Build the data
before serving the site.

The build clears `public/data/entries/` and `public/entry/` before it
writes them, so no stale entry survives a rename. The entry files
arrive fully resolved: tags, affixes, markers and relation rows all
carry their display data. The reasons are in
[doc/design.md](doc/design.md).

### Describe the data

A Dublin Core `metadata.json` next to the DMLex file describes the
resource: the title, the description, the rights, the license and
the sources. Every field is optional, and so is the file itself.
[doc/metadata.md](doc/metadata.md) lists the fields.

### Present the data

A dataset can ship a small `presentation.json` file next to its
DMLex JSON. The file can hide, rename, reorder and group what the
app shows, and it can translate the interface of the app. The keys
are the dataset's own tags, so the app never has to know what a tag
means. [doc/presentation.md](doc/presentation.md) describes every
section of the file.

## Build the frontend

1. Install the npm dependencies: `npm install`
2. Compile the release build: `npx shadow-cljs release app`

## Serve

Point a static file server at `public/`. The JDK that runs the build
brings one (`jwebserver` needs JDK 18 or newer):

```sh
jwebserver -d "$PWD/public"
```

The server answers on <http://localhost:8000>. The host must serve
`index.html` for a directory URL, which `jwebserver` and every static
host do. Nothing else is needed: entry URLs are real files.

The same directory deploys to any production host.
[doc/deploy.md](doc/deploy.md) lists the recommended headers and the
hardening steps.

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
and a Makefile. The entries show the same content as the web app.
The entry shows the short inflected forms, and the search index holds
the full forms. If a Dublin Core `metadata.json` sits next to the DMLex
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

[doc/development.md](doc/development.md) describes the development
watch, the scene workbench, the tests and the translation workflow.
