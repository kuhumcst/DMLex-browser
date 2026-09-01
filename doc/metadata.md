# `metadata.json`

A Dublin Core `metadata.json` next to the DMLex file describes the
resource. The data build merges it into `manifest.json` for the front
page. The Apple dictionary export fills the bundle metadata and the
front matter from the same file. Every field is optional, and so is
the file itself. Without the file, the title, URI and language come
from the DMLex file.

| Field | Meaning |
|---|---|
| `dc:title` | The resource title. It replaces the DMLex `title`. |
| `dc:identifier` | The resource URI. It replaces the DMLex `uri`. |
| `dc:language` | The language of the presentation: the default interface language and the `lang` of the pages. It replaces the DMLex `langCode` everywhere except the collations, which keep the language of the headwords. |
| `dc:description` | A description for the front page: a string, or a map of language codes to strings. The build picks the string for `dc:language` and falls back to English. |
| `dc:publisher` | The publishing institution. |
| `dc:rights` | A rights statement. |
| `dc:license` | The license URL. The app shows a Creative Commons URL as its short name, for example CC BY-SA 4.0. |
| `dc:issued` | The version of the Apple dictionary bundle. |
| `dc:source` | The source works, as a list of maps. Each map has an optional `dc:title`, `dc:identifier` (the home URI) and `dc:license`. A title like `DDO (Den Danske Ordbog)` splits into the abbreviation and the full name. |
