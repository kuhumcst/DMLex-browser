# Deploy

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
| `Cache-Control` | `no-cache` for `index.html`, `entry/`, `js/main.js` and `data/` |

The app loads no third-party resources, so the strict policy is safe.
The file names do not change between builds, so `no-cache` makes the
browser revalidate each file.

Some files stay out of the repository until the site has a stable public
URL: a custom 404 page, the Open Graph tags, a `rel="canonical"` link,
a `sitemap.xml`, and an `llms.txt` for AI agents. The reasons are in
[design.md](design.md), with the record of the audit against
[The Website Specification](https://specification.website/).
