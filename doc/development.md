# Develop and test

Start the development watch:

```sh
npx shadow-cljs watch app
```

The watch compiles on each change and serves `public/` at
<http://localhost:8000>. Build the data first: the watch compiles the
frontend, and the data build writes the pages it loads into.

The views have a scene workbench, which needs no dataset:

```sh
npx shadow-cljs watch portfolio
```

The workbench serves the scenes of `dk.cst.dmlex-browser.views-scenes`
at <http://localhost:8001>, over hand-made DMLex shapes that a real
dataset produces rarely. Each scene renders into
[dev-resources/public/canvas.html](../dev-resources/public/canvas.html),
which gives it the app's own page sheet.

The resolution logic of the data build has JVM tests:

```sh
clojure -M:test
```

The views run on both platforms, so their tests run with the JVM tests
as well. The routing and keyboard logic of the frontend has Node
tests:

```sh
npx shadow-cljs compile test && node out/node-tests.js
```

The translatable strings of the app live in
[i18n/template.pot](../i18n/template.pot). The command `clojure -M:i18n`
regenerates the template from the source. A new UI language is one
more po file in [i18n/](../i18n/) and its entry in
`dk.cst.dmlex-browser.translations`. The JVM tests compare the template
and every bundled po file against a fresh extraction, so neither can
drift from the code.
