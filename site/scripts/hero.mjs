export const STEPS = [
  { focus: "c0", backdrop: "f0", seconds: 2.4 },
  { focus: "t0", backdrop: "g0", seconds: 0.5 },
  { focus: "t1", backdrop: "g1", seconds: 0.4 },
  { focus: "t2", backdrop: "g2", seconds: 0.4 },
  { focus: "t3", backdrop: "g3", seconds: 0.4 },
  { focus: "t4", backdrop: "g4", seconds: 0.4 },
  { focus: "t5", backdrop: null, seconds: 0.7 },
  { focus: "t5", backdrop: null, screen: "apps", seconds: 2.6 },
  { focus: "t5", backdrop: null, seconds: 0.6 },
  { focus: "c3", backdrop: "f3", seconds: 0.5 },
  { focus: "wx", backdrop: "wx", seconds: 2.0 },
  { focus: "c3", backdrop: "f3", seconds: 0.45 },
  { focus: "c2", backdrop: "f2", seconds: 0.4 },
  { focus: "c1", backdrop: "f1", seconds: 0.4 },
];

const RING = (u) => `0 0 0 calc(var(--u) * 6) rgba(0, 0, 0, 0.35), 0 0 0 calc(var(--u) * ${u}) #f2f2f2`;
const NO_RING = "0 0 0 0 rgba(0, 0, 0, 0), 0 0 0 0 rgba(242, 242, 242, 0)";

const CHANNELS = {
  focus: {
    fade: 0.12,
    targets: {
      c0: "card", c1: "card", c2: "card", c3: "card",
      t0: "tile", t1: "tile", t2: "tile", t3: "tile", t4: "tile", t5: "tile",
      wx: "plate",
    },
  },
  backdrop: {
    fade: 0.35,
    targets: { f0: "layer", f1: "layer", f2: "layer", f3: "layer", g0: "layer", g1: "layer", g2: "layer", g3: "layer", g4: "layer", wx: "layer" },
  },
  screen: { fade: 0.25, targets: { apps: "layer" } },
};

const LOOKS = {
  card: { on: `transform: scale(1.05); box-shadow: ${RING(9)};`, off: `transform: scale(1); box-shadow: ${NO_RING};` },
  tile: { on: `transform: scale(1.077); box-shadow: ${RING(9)};`, off: `transform: scale(1); box-shadow: ${NO_RING};` },
  plate: { on: "opacity: 1;", off: "opacity: 0;" },
  layer: { on: "opacity: 1;", off: "opacity: 0;" },
};

const SELECTORS = { focus: ".hero .f-", backdrop: ".hero .b-", screen: ".hero .s-" };

export function duration(steps = STEPS) {
  return steps.reduce((sum, step) => sum + step.seconds, 0);
}

function keyframes(steps, channel, target, fade, look) {
  const total = duration(steps);
  const on = (step) => (step[channel] ?? null) === target;
  const points = [];
  let time = 0;
  steps.forEach((step, i) => {
    const previous = steps[(i - 1 + steps.length) % steps.length];
    if (step.seconds <= fade) throw new Error(`Step ${i} is shorter than the ${channel} fade`);
    points.push([time, on(previous)], [time + fade, on(step)]);
    time += step.seconds;
  });
  points.push([total, on(steps[steps.length - 1])]);
  const pct = (t) => `${((t / total) * 100).toFixed(3).replace(/\.?0+$/, "")}%`;
  return points.map(([t, isOn]) => `  ${pct(t)} { ${isOn ? look.on : look.off} }`).join("\n");
}

export function heroCss(steps = STEPS) {
  const total = duration(steps);
  const blocks = [];
  for (const [channel, { fade, targets }] of Object.entries(CHANNELS)) {
    for (const [target, kind] of Object.entries(targets)) {
      const name = `hero-${channel}-${target}`;
      blocks.push(`@keyframes ${name} {\n${keyframes(steps, channel, target, fade, LOOKS[kind])}\n}`);
      blocks.push(`${SELECTORS[channel]}${target} { animation: ${name} ${total.toFixed(2)}s linear infinite; }`);
    }
  }
  blocks.push("@media (prefers-reduced-motion: reduce) {\n  .hero *, .hero *::before { animation: none !important; }\n}");
  return blocks.join("\n") + "\n";
}
