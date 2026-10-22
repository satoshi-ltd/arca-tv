import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, cp, mkdir, realpath } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import { build, render, publicUrl, localReferences, SCREENS } from "./build.mjs";
import { STEPS, duration, heroCss } from "./hero.mjs";

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
  assert.match(html, /<meta property="og:image" content="https:\/\/tv\.example\.com\/screens\/home\.jpg">/);
  assert.match(html, /<meta name="twitter:image" content="https:\/\/tv\.example\.com\/screens\/home\.jpg">/);
  assert.match(html, /<meta property="og:title" content="arca TV · Your home, on the big screen\.">/);
  assert.match(html, /<meta property="og:description" content="A calm home screen for Google TV and Android TV\. Connect arca and your photos, videos and music come with it\.">/);
  assert.deepEqual((await readdir(path.join(dist, "screens"))).sort(), [...SCREENS].sort());
  for (const file of ["styles.css", "hero.css", "site.js", "_headers", "assets/Geist.ttf", "assets/OFL-Geist.txt", "assets/mark.svg"])
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
  assert.doesNotMatch(html, /<script(?![^>]*\ssrc=)/);
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
  for (const step of STEPS.slice(1)) if (!step.screen && step.focus !== "wx" && step.focus !== "t5") assert.ok(step.seconds <= 0.5);
  assert.ok(duration() < 15);
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
