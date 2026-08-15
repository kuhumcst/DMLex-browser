# Audit record: The Website Specification

Audit date: 2026-08-14, against <https://specification.website/>. The spec
changes often. For the next audit, only examine the delta since this date
(the changelog feed is at <https://specification.website/changelog/rss.xml>).

The viewer is a static site with hash routing and no server, made for
local use first. Each item below is in one of five groups: satisfied in
the repository, delegated to the host, deferred until the site is
published, not applicable, or declined with a reason.

## Satisfied in the repository

- Foundations: doctype, `<meta charset>`, `<meta viewport>`, `<title>`,
  meta description, an SVG favicon, `theme-color`, `color-scheme`.
- The document `lang` comes from the `langCode` of the manifest at run
  time, so the viewer stays dataset-agnostic. English UI strings carry
  their own `lang="en"`.
- The document title names the current entry: `menneske – DanNet`.
- Accessibility: labelled search field, `<main>` landmark, one `h1` on
  each view, visible focus indicator, status line for the result count,
  semantic HTML, colour contrast (all text pairs are at or above 5.2:1;
  most are above 7:1), touch targets, mobile input attributes
  (`enterkeyhint`, `autocapitalize`, 16px+ input text).
- Keyboard: the search field and its suggestions form an ARIA combobox
  (`role="combobox"` over a listbox of options with
  `aria-activedescendant`); the arrow keys move the active suggestion,
  Enter follows it, Escape clears. On navigation, focus moves to the
  new entry's headword (or back to the search field on the front page),
  so focus never rests on an element a re-render removed.
- SEO: heading hierarchy, internal links, implicit index policy.
- Resilience: a friendly in-app error view with a way back, `<noscript>`
  fallback text.
- Performance: system fonts, no images, two small stylesheets, deferred
  script, `scrollbar-gutter: stable`, `text-wrap: pretty`, no animation
  (nothing for `prefers-reduced-motion` to guard).
- Agent readiness: the JSON data files are the machine-readable format of
  the whole dictionary. Entry ids come from the dataset, so entry URLs
  are stable.
- Privacy: no cookies, no third-party scripts, no analytics, no personal
  data. As a result, no consent banner is necessary.

## Delegated to the host

The README section "Deploy" lists these: HTTPS and the HTTP-to-HTTPS
redirect, HSTS, compression, `X-Content-Type-Options`, CSP,
`Referrer-Policy`, and `Cache-Control`.

## Deferred until published

The site is for local use first, so these items wait for a stable public
URL: a custom 404 page, the Open Graph tags, a canonical link, and
`llms.txt`. A `robots.txt` is not necessary: an absent file means the
same as an allow-all file. Install icons and a web app manifest return
only if someone installs the site to a home screen.

## Not applicable

Feeds and WebSub, breadcrumbs, forms beyond the one search field, cookies
and consent, authentication, captions and video, i18n alternates and
`hreflang` (one language per dataset), RTL mirroring (no RTL dataset yet),
`/.well-known/` files for apps, OAuth and the Fediverse.

## Declined, with reasons

- Skip link: only one control (the search field) stands before the main
  content, so there is no repeated block to skip. WCAG 2.4.1 targets
  repeated blocks.
- XML sitemap and server-side rendering: hash routing gives the site one
  crawlable URL. This is a deliberate result of the no-server design.
- Fingerprinted asset names: the extra build complexity does not pay off
  at this scale. The README tells hosts to use `no-cache` instead.
- Structured data (JSON-LD): schema.org has no good type for dictionary
  entries in a hash-routed SPA. The JSON data files serve agents better.
