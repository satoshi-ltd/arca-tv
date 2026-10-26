import { readFile, writeFile, mkdir, cp, rm, access, readdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { createHash } from "node:crypto";
import { heroCss } from "./hero.mjs";

const repo = fileURLToPath(new URL("../../", import.meta.url));

export const SCREENS = [
  "photo-viewer.jpg",
  "music.jpg",
  "photos.jpg",
  "domo.jpg",
  "all-apps.jpg",
  "quick-settings.jpg",
  "settings.jpg",
];

export const OG_ALT = "arca TV on a Google TV: Watch next with your movies, and arca Movies, Photos and Music beside your apps.";

export const HEADERS = `/*
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=(), interest-cohort=()
  Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; connect-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
/assets/*
  Cache-Control: public, max-age=604800
/screens/*
  Cache-Control: public, max-age=86400
`;

const escape = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );

export function publicUrl(value) {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash)
    throw new Error("Expected a public HTTPS URL without credentials, query or fragment");
  return url.href.replace(/\/$/, "");
}

const plain = (value) =>
  String(value)
    .replace(/<[^>]+>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&rsquo;|’/g, "’")
    .replace(/\s+/g, " ")
    .trim();

const json = (value) => `<script type="application/ld+json">${JSON.stringify(value).replace(/</g, "\\u003c")}</script>`;

export function questions(template) {
  return [...template.matchAll(/<details class="glass"><summary>([\s\S]*?)<svg[\s\S]*?<\/summary><p>([\s\S]*?)<\/p>/g)].map(
    ([, question, answer]) => ({ question: plain(question), answer: plain(answer) }),
  );
}

export function render(template, config = {}) {
  const origin = config.siteUrl ? publicUrl(config.siteUrl) : "";
  const absolute = (file) => (origin ? `${origin}/${file}` : file);
  const title = plain(/<title>([\s\S]*?)<\/title>/.exec(template)?.[1] || "arca TV");
  const description = plain(/<meta name="description" content="([^"]*)">/.exec(template)?.[1] || "");
  const social = [
    ["property", "og:type", "website"],
    ["property", "og:site_name", "arca TV"],
    ["property", "og:locale", "en_GB"],
    ["property", "og:title", title],
    ["property", "og:description", description],
    origin && ["property", "og:url", `${origin}/`],
    ["property", "og:image", absolute("assets/og-image.jpg")],
    ["property", "og:image:type", "image/jpeg"],
    ["property", "og:image:width", "1200"],
    ["property", "og:image:height", "630"],
    ["property", "og:image:alt", OG_ALT],
    ["name", "twitter:card", "summary_large_image"],
    ["name", "twitter:title", title],
    ["name", "twitter:description", description],
    ["name", "twitter:image", absolute("assets/og-image.jpg")],
    ["name", "twitter:image:alt", OG_ALT],
  ]
    .filter(Boolean)
    .map(([kind, key, value]) => `<meta ${kind}="${key}" content="${escape(value)}">`)
    .join("\n");
  const faq = questions(template);
  const structured = [
    json({
      "@context": "https://schema.org",
      "@type": "SoftwareApplication",
      name: "arca TV",
      description,
      applicationCategory: "MultimediaApplication",
      operatingSystem: "Android TV 9+, Google TV",
      ...(config.version ? { softwareVersion: config.version } : {}),
      ...(origin ? { url: `${origin}/` } : {}),
      image: absolute("assets/og-image.jpg"),
      downloadUrl: "https://github.com/satoshi-ltd/arca-tv",
      license: "https://github.com/satoshi-ltd/arca-tv/blob/main/LICENSE",
      isAccessibleForFree: true,
      offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
      author: { "@type": "Organization", name: "Satoshi Ltd.", url: "https://www.satoshi-ltd.com" },
    }),
    faq.length &&
      json({
        "@context": "https://schema.org",
        "@type": "FAQPage",
        mainEntity: faq.map((entry) => ({ "@type": "Question", name: entry.question, acceptedAnswer: { "@type": "Answer", text: entry.answer } })),
      }),
  ]
    .filter(Boolean)
    .join("\n");
  const tokens = {
    ASSET_VERSION: config.assetVersion || "dev",
    CANONICAL: origin ? `<link rel="canonical" href="${escape(origin)}/">` : "",
    SOCIAL: social,
    STRUCTURED_DATA: structured,
  };
  return template.replace(/\{\{([A-Z_]+)\}\}/g, (_, key) => {
    if (!(key in tokens)) throw new Error(`Unknown template token: ${key}`);
    return tokens[key];
  });
}

export function robots(siteUrl) {
  return `User-agent: *\nAllow: /\n${siteUrl ? `Sitemap: ${publicUrl(siteUrl)}/sitemap.xml\n` : ""}`;
}

export function sitemap(siteUrl) {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n  <url><loc>${escape(publicUrl(siteUrl))}/</loc></url>\n</urlset>\n`;
}

export function localReferences(html, css) {
  const refs = new Set();
  for (const [, ref] of html.matchAll(/\s(?:src|href)="([^"]+)"/g)) refs.add(ref);
  for (const [, ref] of css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)) refs.add(ref);
  return [...refs].filter((ref) => !/^(https?:|#|mailto:|data:)/.test(ref)).map((ref) => ref.split(/[?#]/)[0]);
}

export async function demoCss(site) {
  const names = (await readdir(path.join(site, "demos")).catch(() => [])).filter((name) => name.endsWith(".css")).sort();
  const parts = await Promise.all(names.map((name) => readFile(path.join(site, "demos", name), "utf8")));
  return parts.join("\n");
}

export async function build({ root = repo, output, siteUrl } = {}) {
  const site = path.join(root, "site");
  const dist = path.resolve(output || path.join(site, "dist"));
  await rm(dist, { recursive: true, force: true });
  await mkdir(path.join(dist, "screens"), { recursive: true });
  const css = await readFile(path.join(site, "styles.css"), "utf8");
  const script = await readFile(path.join(site, "site.js"), "utf8");
  const animation = heroCss();
  const demos = await demoCss(site);
  const assetVersion = createHash("sha256").update(css + script + animation + demos).digest("hex").slice(0, 12);
  const manifest = await readFile(path.join(root, "app/AndroidManifest.xml"), "utf8").catch(() => "");
  const version = /versionName="([^"]+)"/.exec(manifest)?.[1] || "";
  const html = render(await readFile(path.join(site, "index.html"), "utf8"), { siteUrl, assetVersion, version });
  await writeFile(path.join(dist, "index.html"), html);
  await writeFile(path.join(dist, "styles.css"), css);
  await writeFile(path.join(dist, "hero.css"), animation);
  await writeFile(path.join(dist, "demos.css"), demos);
  await writeFile(path.join(dist, "site.js"), script);
  await cp(path.join(site, "assets"), path.join(dist, "assets"), { recursive: true });
  await cp(path.join(root, "app/assets/fonts/Geist.ttf"), path.join(dist, "assets/Geist.ttf"));
  await cp(path.join(root, "app/assets/fonts/OFL-Geist.txt"), path.join(dist, "assets/OFL-Geist.txt"));
  for (const name of SCREENS)
    await cp(path.join(root, "docs/screens", name), path.join(dist, "screens", name));
  await writeFile(path.join(dist, "_headers"), HEADERS);
  await writeFile(path.join(dist, "robots.txt"), robots(siteUrl));
  if (siteUrl) await writeFile(path.join(dist, "sitemap.xml"), sitemap(siteUrl));
  const missing = [];
  for (const ref of localReferences(html, css + demos)) {
    await access(path.join(dist, decodeURI(ref))).catch(() => missing.push(ref));
  }
  if (missing.length) throw new Error(`Missing site files: ${missing.join(", ")}`);
  return dist;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const dist = await build({ output: process.env.SITE_OUTPUT, siteUrl: process.env.SITE_URL });
  console.log(`Built ${path.relative(process.cwd(), dist) || dist}`);
}
