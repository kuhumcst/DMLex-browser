# `presentation.json`

A dataset can ship a small `presentation.json` file next to its
DMLex JSON. The file holds the presentation choices of the dataset.
It can hide, rename, reorder and group the label types and the
relation types, and it can rename the relation roles. The keys are
the dataset's own tags. The app applies the operations and never has
to know what a tag means.

Without the file, the app shows the dataset's own names and order.
The app ignores unknown sections and unknown tags.

The data build carries the file into `public/data/`, and the Apple
dictionary export reads it next to its input file. On an entry page,
the web app has a checkbox that turns the config off, to show the
neutral default view. The browser remembers the choice per dataset.

```jsonc
{
  "labelTypes": {
    "order":    ["domain", "register"],
    "hide":     ["spelling"],
    "unlisted": "hide",              // or "after" (the default)
    "rename":   {"domain": "emne"},
    "combine":  {"sentiment": "sentimentValue"},
    "show":     {"synset": "description"},
    "inline":   ["sentiment"],
    "cite":     ["source"]
  },
  "relationTypes": {
    "order":  ["synonym"],
    "groups": [{"title": "Betydning", "types": ["synonym", "antonym"]},
               {"title": "Andre relationer"}]
  },
  "roles":         {"rename": {"hypernym": {"da": "overbegreb",
                                            "en": "broader"}}},
  "memberOrder":   "collation",      // or "listing" (the default)
  "linkResolver":  "https://wordnet.dk/dannet/external?subject=",
  "css":           "extra.css",

  // Translations of the app's chrome into the dataset's language.
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

The design decisions behind the config are in
[design.md](design.md).

## Names in more than one language

Wherever the config gives a display name, it can give one per language
instead of one string:

```jsonc
"rename": {"domain": {"da": "emne", "en": "subject"}}
```

The app resolves the name to the language the reader picked, then
to the language of the resource. English stands in for a language
that the config does not name. When English is also missing, the
first name in the order of the language codes stands in. So a name
is never lost.

This applies to the renames of label types, relation types and
roles. It also applies to the title and description of a relation
group, and to the fields of the `appledict` section. A resource
exports once per language, but its config need not. The operations
are the same in every language, and only the names differ. So one
config can ship with every export.

The text of the dataset stays in the language of its export. The
names follow the reader. The definitions, the examples and the
descriptions of the inventory do not.

## `labelTypes` and `relationTypes`

Both sections take the same four operations over their tags:

- `order` lists the tags that come first, in this order. The other
  tags keep the dataset's order after them.
- `unlisted` applies to the tags that are not in `order`. The value
  `"after"` (the default) keeps them, and `"hide"` removes them.
- `hide` lists the tags that never show. A hidden tag stays hidden
  through every other operation.
- `rename` maps a tag to its displayed name. Only the displayed name
  changes. The tag stays the key everywhere else.

Label types take three more:

- `combine` maps a host type to a qualifier type. The values of the
  qualifier show on the host label as "value (qualifier)", and the
  labels of the qualifier disappear. A qualifier without a host stays
  an ordinary label. Nothing disappears by accident.
- `show` maps a type to `"description"`: the labels of that type show
  their description, and the technical tag moves into the tooltip.
  A label without a description keeps its tag.
- `inline` lists the label types that move out of the entry's labels
  box and onto the part-of-speech line, in this order. A dot
  separator comes before each one. The other operations run first,
  so a hidden type stays hidden and renames carry over. Senses keep
  these types in their own label lists. When every entry label moves,
  the labels box disappears.
- `cite` lists the label types that move out of a sense's labels box
  and onto its meaning line, after the definitions, as a citation.
  Each shows the displayed name of its type, linked to whatever the
  label points at. A label whose tag is an identifier rather than a
  word still reads. A dataset's source reference belongs here.

Relation types take one more:

- `groups` gathers the relation rows into titled sections. Each group
  has an optional `title` and `description`, and a `types` vector
  that claims its rows, in that order. A group without `types` is the
  fallback for every unclaimed row. If no group is the fallback and
  `unlisted` is not `"hide"`, the unclaimed rows form a trailing
  group without a title. Empty groups disappear.

## `roles`

`rename` maps a relation role to its displayed name, the same way as
a tag rename.

## `memberOrder`

With `"listing"` (the default) the members of a relation row keep the
listing order of the dataset. `"collation"` sorts them by the
`obverseListingOrder` of each member first, then by the headword in
the collation of the resource language. A member without an order
sorts after every member with one.

The web app also has a checkbox
that forces a strictly alphabetical order, whatever `memberOrder`
says. The checkbox starts unchecked, and the browser remembers the
choice per dataset. The Apple dictionary has no checkbox, so there
the setting decides alone. DanNet, for example, derives the member order
from how many relations point at each synset. With `"collation"` the
most central words then come first.

## `linkResolver`

A URL prefix that reroutes every `sameAs` link through the dataset's
own resource browser (vocabulary URIs usually serve raw RDF files,
which help no reader). The link becomes the prefix plus the
percent-encoded URI. Links already on the resolver's host stay
direct.

## `css`

The name of a stylesheet next to the DMLex file. The data build
copies it into `public/data/`. The Apple dictionary export bundles it
before the `appledict` stylesheet.

## `ui`

The app's own interface strings are English. The `ui` section
translates them in the gettext style. The English string is its own
key, `{n}` carries a count, and singular and plural are separate keys
(`"1 match shown"`, `"{n} matches shown"`). An untranslated string
stays English and keeps its `lang="en"` marker for assistive
technology. The app treats a translated string as text in the
language of the resource.

The Apple dictionary export applies the same table at export time.
This also covers the strings that its stylesheet shows as CSS
content.

The app bundles a Danish translation ([i18n/da.po](../i18n/da.po))
and picks it by the `langCode` of the resource. The web app also
has a dropdown that switches the UI language. The browser remembers
the choice per dataset. A dataset can override or extend the bundled
table with its own `ui` section. It can also ship the translations as
a gettext `ui.po` next to its DMLex file (the format that translation
tools like Poedit produce). The builds merge the po file over the
`ui` section.

## `appledict`

Only the Apple dictionary export reads this section. `identifier`
replaces the bundle identifier. `css` names a stylesheet that the
export bundles after the shared one. `frontMatter` names an HTML
fragment that becomes the front matter of the dictionary.
