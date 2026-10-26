import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, cp, mkdir, realpath } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import { build, render, publicUrl, localReferences, questions, robots, sitemap, SCREENS } from "./build.mjs";
import { STEPS, duration, heroCss } from "./hero.mjs";
import vm from "node:vm";

const repo = fileURLToPath(new URL("../../", import.meta.url));

async function temp(t) {
  const dir = await realpath(await mkdtemp(path.join(os.tmpdir(), "arca-tv-site-")));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

test("build writes the page, styles, script, assets, screens and headers", async (t) => {
  const dist = await build({ output: path.join(await temp(t), "dist"), siteUrl: "https://tv.example.com/" });
  const html = await readFile(path.join(dist, "index.html"), "utf8");
  assert.match(html, /<link rel="canonical" href="https:\/\/tv\.example\.com\/">/);
  assert.doesNotMatch(html, /\{\{/);
  assert.match(html, /<meta property="og:image" content="https:\/\/tv\.example\.com\/assets\/og-image\.jpg">/);
  assert.match(html, /<meta name="twitter:image" content="https:\/\/tv\.example\.com\/assets\/og-image\.jpg">/);
  assert.match(html, /<meta property="og:url" content="https:\/\/tv\.example\.com\/">/);
  assert.match(html, /<meta property="og:title" content="arca TV · [^"]+">/);
  assert.match(html, /<meta property="og:image:width" content="1200">/);
  const ld = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((m) => JSON.parse(m[1]));
  assert.equal(ld[0]["@type"], "SoftwareApplication");
  assert.equal(ld[0].softwareVersion, /versionName="([^"]+)"/.exec(await readFile(path.join(repo, "app/AndroidManifest.xml"), "utf8"))[1]);
  assert.equal(ld[1]["@type"], "FAQPage");
  assert.ok(ld[1].mainEntity.length >= 5);
  assert.match(await readFile(path.join(dist, "robots.txt"), "utf8"), /Sitemap: https:\/\/tv\.example\.com\/sitemap\.xml/);
  assert.match(await readFile(path.join(dist, "sitemap.xml"), "utf8"), /<loc>https:\/\/tv\.example\.com\/<\/loc>/);
  assert.deepEqual((await readdir(path.join(dist, "screens"))).sort(), [...SCREENS].sort());
  for (const file of ["styles.css", "hero.css", "demos.css", "site.js", "assets/og-image.jpg", "assets/apple-touch-icon.png", "_headers", "assets/Geist.ttf", "assets/OFL-Geist.txt", "assets/mark.svg"])
    await readFile(path.join(dist, file));
  const headers = await readFile(path.join(dist, "_headers"), "utf8");
  assert.match(headers, /script-src 'self'; style-src 'self'/);
});

test("build without SITE_URL leaves no canonical link", async (t) => {
  const dist = await build({ output: path.join(await temp(t), "dist") });
  assert.doesNotMatch(await readFile(path.join(dist, "index.html"), "utf8"), /rel="canonical"/);
});

test("build replaces a stale dist", async (t) => {
  const output = path.join(await temp(t), "dist");
  await mkdir(path.join(output, "assets"), { recursive: true });
  await cp(path.join(repo, "site/site.js"), path.join(output, "assets/retired.js"));
  await build({ output });
  await assert.rejects(readFile(path.join(output, "assets/retired.js")));
});

test("build fails when a referenced file is missing", async (t) => {
  const root = await temp(t);
  for (const dir of ["site", "app/assets/fonts", "docs/screens"])
    await cp(path.join(repo, dir), path.join(root, dir), { recursive: true });
  await rm(path.join(root, "site/assets/films/interstellar.jpg"));
  await assert.rejects(build({ root, output: path.join(root, "out") }), /Missing site files: assets\/films\/interstellar\.jpg/);
});

test("page stays within the content security policy", async () => {
  const html = await readFile(path.join(repo, "site/index.html"), "utf8");
  const css = await readFile(path.join(repo, "site/styles.css"), "utf8");
  assert.doesNotMatch(html, /\sstyle="/);
  assert.doesNotMatch(html.replace(/\{\{STRUCTURED_DATA\}\}/, ""), /<script(?![^>]*\s(?:src=|type="application\/ld\+json"))/);
  assert.doesNotMatch(html, /\son[a-z]+="/);
  assert.doesNotMatch(html, /\ssrc="https?:/);
  assert.doesNotMatch(html, /<link[^>]+href="https?:\/\/(?!github)/);
  assert.doesNotMatch(css, /url\(\s*["']?https?:/);
  for (const name of await readdir(path.join(repo, "site/assets/apps")))
    assert.doesNotMatch(await readFile(path.join(repo, "site/assets/apps", name), "utf8"), /\sstyle=|<script/i, name);
});

test("local references cover src, href and css urls but skip anchors and external links", () => {
  const refs = localReferences('<img src="a.png"><a href="#top"></a><a href="https://x.dev"></a><link href="s.css?v=123">', 'b{background:url("c.svg")}');
  assert.deepEqual(refs.sort(), ["a.png", "c.svg", "s.css"]);
});

test("render rejects unknown tokens and unsafe site urls", () => {
  assert.throws(() => render("{{NOPE}}"), /Unknown template token/);
  assert.throws(() => render("{{CANONICAL}}", { siteUrl: "http://tv.example.com" }), /public HTTPS/);
  assert.throws(() => publicUrl("https://user:pass@tv.example.com"), /public HTTPS/);
  assert.equal(publicUrl("https://tv.example.com/"), "https://tv.example.com");
});

test("hero journey starts on the first film, opens All apps, rests on the weather and loops back", () => {
  assert.equal(STEPS[0].focus, "c0");
  assert.equal(STEPS[0].backdrop, "f0");
  const apps = STEPS.findIndex((step) => step.screen === "apps");
  assert.ok(apps > 0 && STEPS[apps - 1].focus === "t5" && STEPS[apps + 1].focus === "t5");
  assert.ok(STEPS.some((step) => step.focus === "wx" && step.seconds >= 1.5));
  assert.equal(STEPS.at(-1).focus, "c1");
  for (const step of STEPS) assert.ok(step.seconds >= 0.8, "each focus has time to settle");
  assert.ok(duration() >= 20 && duration() <= 30);
});

test("the hub instructions use the current library setup names", async () => {
  const html = await readFile(path.join(repo, "site/index.html"), "utf8");
  assert.doesNotMatch(html, /Enable gallery|Enable music library/);
  assert.match(html, /Set up as library… → Photos/);
  assert.match(html, /Set up as library… → Audio/);
});

test("only the hero images load eagerly, including in the standalone demos", async () => {
  const html = await readFile(path.join(repo, "site/index.html"), "utf8");
  const split = html.indexOf('<section id="arca"');
  for (const [tag] of html.slice(0, split).matchAll(/<img\b[^>]*>/g)) assert.doesNotMatch(tag, /loading="lazy"/);
  for (const source of [html.slice(split), ...await Promise.all(["movies", "photos", "music"].map((name) => readFile(path.join(repo, "site/demos", `${name}.html`), "utf8")))])
    for (const [tag] of source.matchAll(/<img\b[^>]*>/g)) assert.match(tag, /loading="lazy"/);
});

test("every demo, including Home, pauses out of view without losing the reader's pause", async () => {
  const nodes = ["hero", "demo"].map((name) => {
    const classes = new Set([name]);
    return { classes, classList: { add: (value) => classes.add(value), toggle(value, on) { on ? classes.add(value) : classes.delete(value); } } };
  });
  const observed = [];
  let notify;
  vm.runInNewContext(await readFile(path.join(repo, "site/site.js"), "utf8"), {
    window: { IntersectionObserver: true },
    IntersectionObserver: function (callback) { notify = callback; this.observe = (node) => observed.push(node); },
    document: { addEventListener() {}, querySelectorAll: (selector) => nodes.filter((node) => selector.split(",").some((part) => node.classes.has(part.trim().slice(1)))) },
  });
  assert.equal(observed.length, 2);
  for (const node of nodes) {
    assert.ok(node.classes.has("is-away"));
    notify([{ target: node, isIntersecting: true, intersectionRatio: 0.5 }]);
    assert.equal(node.classes.has("is-away"), false);
    node.classes.add("is-paused");
    notify([{ target: node, isIntersecting: true, intersectionRatio: 0.1 }]);
    assert.ok(node.classes.has("is-away"));
    notify([{ target: node, isIntersecting: true, intersectionRatio: 0.5 }]);
    assert.ok(node.classes.has("is-paused"));
  }
});

test("movie still frames hide Home and all demos give each scene more reading time", async () => {
  const css = await readFile(path.join(repo, "site/demos/movies.css"), "utf8");
  const reduced = css.slice(css.indexOf("@media (prefers-reduced-motion: reduce)"));
  assert.match(reduced, /\.mv-home[^}]*opacity: 0/);
  assert.match(css, /\.demo-movies\.is-paused \.mv-home[^}]*opacity: 0/);
  const common = await readFile(path.join(repo, "site/styles.css"), "utf8");
  assert.match(common, /\.hero\.is-away[^}]*animation-play-state: paused/);
  for (const name of ["movies", "photos", "music"]) {
    const animation = await readFile(path.join(repo, "site/demos", `${name}.css`), "utf8");
    const durations = [...animation.matchAll(/animation: [^;{}]+? ([\d.]+)s linear infinite/g)].map((match) => Number(match[1]));
    assert.ok(durations.length > 0 && durations.every((seconds) => seconds >= 20), name);
  }
});

test("hero css animates every focus target, backdrop and screen on one loop", async () => {
  const css = heroCss();
  const html = await readFile(path.join(repo, "site/index.html"), "utf8");
  const total = `${duration().toFixed(2)}s`;
  for (const [, cls] of html.matchAll(/class="[^"]*\b([fbs]-[a-z0-9]+)\b/g)) {
    assert.match(css, new RegExp(`\\.${cls} \\{ animation: hero-[a-z]+-${cls.slice(2)} ${total} linear infinite; \\}`), cls);
  }
  for (const block of css.matchAll(/@keyframes [^{]+\{([^@]*?)\n\}/g)) {
    const stops = [...block[1].matchAll(/([\d.]+)% \{/g)].map((m) => Number(m[1]));
    assert.equal(stops[0], 0);
    assert.equal(stops.at(-1), 100);
    for (let i = 1; i < stops.length; i++) assert.ok(stops[i] >= stops[i - 1]);
  }
  assert.match(css, /prefers-reduced-motion: reduce/);
});

test("hero rejects a step shorter than its fade", () => {
  assert.throws(() => heroCss([{ focus: "c0", backdrop: "f0", seconds: 1 }, { focus: "t0", backdrop: "g0", seconds: 0.2 }]), /shorter than the backdrop fade/);
});

test("faq questions feed the structured data as plain text", () => {
  const faq = questions('<details class="glass"><summary>Is it <b>free</b>?<svg></svg></summary><p>Yes &amp; <a href="#">always</a>.</p></details>');
  assert.deepEqual(faq, [{ question: "Is it free?", answer: "Yes & always." }]);
});

test("robots points at the sitemap only with a site url", () => {
  assert.equal(robots(), "User-agent: *\nAllow: /\n");
  assert.match(robots("https://tv.example.com"), /Sitemap: https:\/\/tv\.example\.com\/sitemap\.xml/);
  assert.match(sitemap("https://tv.example.com/"), /<loc>https:\/\/tv\.example\.com\/<\/loc>/);
});
