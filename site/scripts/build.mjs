import { readFile, writeFile, mkdir, cp, rm, access } from "node:fs/promises";
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

export function render(template, config = {}) {
  const tokens = {
    ASSET_VERSION: config.assetVersion || "dev",
    CANONICAL: config.siteUrl
      ? `<link rel="canonical" href="${escape(publicUrl(config.siteUrl))}/">`
      : "",
    SOCIAL_IMAGE: config.siteUrl
      ? `<meta property="og:image" content="${escape(publicUrl(config.siteUrl))}/screens/photos.jpg">\n<meta property="og:image:alt" content="arca Photos timeline on TV">\n<meta name="twitter:image" content="${escape(publicUrl(config.siteUrl))}/screens/photos.jpg">`
      : "",
  };
  return template.replace(/\{\{([A-Z_]+)\}\}/g, (_, key) => {
    if (!(key in tokens)) throw new Error(`Unknown template token: ${key}`);
    return tokens[key];
  });
}

export function localReferences(html, css) {
  const refs = new Set();
  for (const [, ref] of html.matchAll(/\s(?:src|href)="([^"]+)"/g)) refs.add(ref);
  for (const [, ref] of css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)) refs.add(ref);
  return [...refs].filter((ref) => !/^(https?:|#|mailto:|data:)/.test(ref)).map((ref) => ref.split(/[?#]/)[0]);
}

export async function build({ root = repo, output, siteUrl } = {}) {
  const site = path.join(root, "site");
  const dist = path.resolve(output || path.join(site, "dist"));
  await rm(dist, { recursive: true, force: true });
  await mkdir(path.join(dist, "screens"), { recursive: true });
  const css = await readFile(path.join(site, "styles.css"), "utf8");
  const script = await readFile(path.join(site, "site.js"), "utf8");
  const animation = heroCss();
  const assetVersion = createHash("sha256").update(css + script + animation).digest("hex").slice(0, 12);
  const html = render(await readFile(path.join(site, "index.html"), "utf8"), { siteUrl, assetVersion });
  await writeFile(path.join(dist, "index.html"), html);
  await writeFile(path.join(dist, "styles.css"), css);
  await writeFile(path.join(dist, "hero.css"), animation);
  await writeFile(path.join(dist, "site.js"), script);
  await cp(path.join(site, "assets"), path.join(dist, "assets"), { recursive: true });
  await cp(path.join(root, "app/assets/fonts/Geist.ttf"), path.join(dist, "assets/Geist.ttf"));
  await cp(path.join(root, "app/assets/fonts/OFL-Geist.txt"), path.join(dist, "assets/OFL-Geist.txt"));
  for (const name of SCREENS)
    await cp(path.join(root, "docs/screens", name), path.join(dist, "screens", name));
  await writeFile(path.join(dist, "_headers"), HEADERS);
  const missing = [];
  for (const ref of localReferences(html, css)) {
    await access(path.join(dist, decodeURI(ref))).catch(() => missing.push(ref));
  }
  if (missing.length) throw new Error(`Missing site files: ${missing.join(", ")}`);
  return dist;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const dist = await build({ output: process.env.SITE_OUTPUT, siteUrl: process.env.SITE_URL });
  console.log(`Built ${path.relative(process.cwd(), dist) || dist}`);
}
