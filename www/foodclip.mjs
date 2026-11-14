// Food on a mixed plate, on the phone: the image side of OpenAI CLIP ViT-B/16 (MIT licence, weights in 8 bits, about 100 MB)
// compares 6 parts of the photo with the food descriptions in data/clip-labels.js (their text side was computed on the PC).
// The model is downloaded once from this app's GitHub releases (by the Android side, see ModelStorePlugin) and kept
// in the app's files; after that everything works offline.
import * as ort from "./vendor/ort/ort.wasm.min.mjs";

ort.env.wasm.wasmPaths = new URL("./vendor/ort/", location.href).href;
ort.env.wasm.numThreads = 1;

const MODEL_URL = "https://github.com/chibangar/diario-do-prato/releases/download/modelo-alimentos-1/clip-b16-q8.onnx";
const MODEL_NAME = "clip-b16-q8.onnx", MODEL_BYTES = 99753823;
const MODEL_MB = Math.round(MODEL_BYTES / 1e6);
// the older, less precise model (ViT-B/32) was kept in the web cache: free its 64 MB
caches.delete("ddp-models-v1").catch(() => {}); caches.delete("ddp-models-v2").catch(() => {});
const native = () => { const C = window.Capacitor; return C?.isNativePlatform?.() && typeof C.nativePromise === "function" ? C : null; };
const SIZE = 224, MEAN = [0.48145466, 0.4578275, 0.40821073], STD = [0.26862954, 0.26130258, 0.27577711];

let session = null, opening = null, downloading = null;
let text = null;
function textMatrix() {
  if (text) return text;
  const bin = atob(window.CLIP_TEXT || ""), bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  text = new Float32Array(bytes.buffer);
  return text;
}
// where the model is on the phone (null when it is not there yet)
async function stored() {
  const C = native(); if (!C) return null;
  try { const r = await C.nativePromise("ModelStore", "info", { name: MODEL_NAME }); return r?.exists && r.size === MODEL_BYTES ? r.path : null; } catch { return null; }
}
async function ready() { return !!(await stored()); }
// download once, with progress (0..1); resolves when the model is stored
function download(onProgress) {
  if (downloading) return downloading;
  downloading = (async () => {
    const C = native(); if (!C) throw new Error("only in the app");
    await C.nativePromise("ModelStore", "download", { url: MODEL_URL, name: MODEL_NAME, size: MODEL_BYTES });
    for (;;) {
      await new Promise((r) => setTimeout(r, 400));
      const st = await C.nativePromise("ModelStore", "status", {});
      if (st.state === "done") break;
      if (st.state === "error") throw new Error(st.error || "download");
      onProgress?.(Math.min(0.99, (st.progress || 0) / 100));
    }
    onProgress?.(1);
    return true;
  })().finally(() => { downloading = null; });
  return downloading;
}
async function open() {
  if (session) return session;
  if (!opening) opening = (async () => {
    const path = await stored();
    if (!path) throw new Error("not downloaded");
    const buf = new Uint8Array(await (await fetch(window.Capacitor.convertFileSrc("file://" + path))).arrayBuffer());
    session = await ort.InferenceSession.create(buf, { executionProviders: ["wasm"], graphOptimizationLevel: "all" });
    return session;
  })().catch((e) => { opening = null; throw e; });
  return opening;
}
// one part of the photo -> CLIP input: centre square of the part, 224 x 224, normalised
const work = document.createElement("canvas"); work.width = work.height = SIZE;
const wctx = work.getContext("2d", { willReadFrequently: true });
function pixels(src, x, y, w, h) {
  const side = Math.min(w, h), sx = x + (w - side) / 2, sy = y + (h - side) / 2;
  wctx.imageSmoothingQuality = "high";
  wctx.drawImage(src, sx, sy, side, side, 0, 0, SIZE, SIZE);
  const d = wctx.getImageData(0, 0, SIZE, SIZE).data, n = SIZE * SIZE, out = new Float32Array(3 * n);
  for (let i = 0; i < n; i++) for (let c = 0; c < 3; c++) out[c * n + i] = (d[i * 4 + c] / 255 - MEAN[c]) / STD[c];
  return out;
}
// returns, for the whole photo, its 4 quarters and the centre, the probability of every label.
// few = true: only the whole photo and its centre (used for the extra angles, to keep it quick).
// All parts go through the model at once, which is much faster than one by one.
async function analyzeNow(src, few) {
  const s = await open(), T = textMatrix(), L = T.length / 512;
  const W = src.width, H = src.height, regions = [[0, 0, W, H]];
  for (const [fx, fy] of few ? [[0.25, 0.25]] : [[0, 0], [0.5, 0], [0, 0.5], [0.5, 0.5], [0.25, 0.25]]) regions.push([fx * W, fy * H, W / 2, H / 2]);
  const n = 3 * SIZE * SIZE, batch = new Float32Array(n * regions.length);
  regions.forEach(([x, y, w, h], i) => batch.set(pixels(src, x, y, w, h), i * n));
  const r = await s.run({ pixel_values: new ort.Tensor("float32", batch, [regions.length, 3, SIZE, SIZE]) });
  const all = (r.image_embeds || Object.values(r)[0]).data, out = [];
  for (let ri = 0; ri < regions.length; ri++) {
    const e = all.subarray(ri * 512, ri * 512 + 512);
    let norm = 0; for (let k = 0; k < 512; k++) norm += e[k] * e[k]; norm = Math.sqrt(norm);
    const sims = new Float32Array(L);
    let mx = -Infinity;
    for (let i = 0; i < L; i++) { let d = 0; for (let k = 0; k < 512; k++) d += T[i * 512 + k] * e[k]; sims[i] = d / norm * 100; if (sims[i] > mx) mx = sims[i]; }
    let z = 0; for (let i = 0; i < L; i++) { sims[i] = Math.exp(sims[i] - mx); z += sims[i]; }
    for (let i = 0; i < L; i++) sims[i] /= z;
    out.push(sims);
  }
  return { regions: out, labels: window.CLIP_LABELS || [] };
}
// available: false outside the Android app (the model is downloaded by the app itself)
// one analysis at a time (the live camera and the final photo share the model)
let queue = Promise.resolve();
function analyze(src, few) { const p = queue.then(() => analyzeNow(src, few)); queue = p.catch(() => {}); return p; }
window.FoodClip = { ready, download, open, analyze, MODEL_MB, available: !!native() };
