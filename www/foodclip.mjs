// Food on a mixed plate, on the phone: the image side of OpenAI CLIP ViT-B/32 (MIT licence, 4-bit, about 64 MB)
// compares 6 parts of the photo with the food descriptions in data/clip-labels.js (their text side was computed on the PC).
// The model is downloaded once from Hugging Face and kept in the app's cache; after that everything works offline.
import * as ort from "./vendor/ort/ort.wasm.min.mjs";

ort.env.wasm.wasmPaths = new URL("./vendor/ort/", location.href).href;
ort.env.wasm.numThreads = 1;

const MODEL_URL = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523/onnx/vision_model_q4.onnx";
const MODEL_MB = 64;
const CACHE = "ddp-models-v1";
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
async function cached() {
  try { const c = await caches.open(CACHE); return await c.match(MODEL_URL); } catch { return null; }
}
async function ready() { return !!(await cached()); }
// download once, with progress (0..1); resolves when the model is stored
function download(onProgress) {
  if (downloading) return downloading;
  downloading = (async () => {
    const res = await fetch(MODEL_URL);
    if (!res.ok) throw new Error("download " + res.status);
    const total = Number(res.headers.get("content-length")) || MODEL_MB * 1e6;
    const reader = res.body.getReader(), parts = [];
    let got = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parts.push(value); got += value.length;
      onProgress?.(Math.min(0.99, got / total));
    }
    const blob = new Blob(parts, { type: "application/octet-stream" });
    const c = await caches.open(CACHE);
    await c.put(MODEL_URL, new Response(blob, { headers: { "Content-Type": "application/octet-stream", "Content-Length": String(blob.size) } }));
    onProgress?.(1);
    return true;
  })().finally(() => { downloading = null; });
  return downloading;
}
async function open() {
  if (session) return session;
  if (!opening) opening = (async () => {
    const r = await cached();
    if (!r) throw new Error("not downloaded");
    const buf = new Uint8Array(await r.arrayBuffer());
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
// returns, for the whole photo, its 4 quarters and the centre, the probability of every label
async function analyze(src) {
  const s = await open(), T = textMatrix(), L = T.length / 512;
  const W = src.width, H = src.height, regions = [[0, 0, W, H]];
  for (const [fx, fy] of [[0, 0], [0.5, 0], [0, 0.5], [0.5, 0.5], [0.25, 0.25]]) regions.push([fx * W, fy * H, W / 2, H / 2]);
  const out = [];
  for (const [x, y, w, h] of regions) {
    const feeds = { pixel_values: new ort.Tensor("float32", pixels(src, x, y, w, h), [1, 3, SIZE, SIZE]) };
    const r = await s.run(feeds);
    const e = (r.image_embeds || Object.values(r)[0]).data;
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
window.FoodClip = { ready, download, open, analyze, MODEL_MB };
