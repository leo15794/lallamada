import { auth, getConfig, setKV } from './_lib.js';

const INTS = { ring: [10, 120], pause: [0, 3600], hStart: [0, 24], hEnd: [0, 24] };
const METHODS = ['mtk', 'directo', 'altavoz'];

export default async function handler(req, res) {
  if (!auth(req, res)) return;
  if (req.method !== 'POST') return res.status(405).end();
  const body = req.body || {};
  const cfg = await getConfig();
  for (const [k, [lo, hi]] of Object.entries(INTS)) {
    if (body[k] !== undefined && body[k] !== '') {
      const v = parseInt(body[k], 10);
      if (!Number.isNaN(v)) cfg[k] = Math.min(hi, Math.max(lo, v));
    }
  }
  if (METHODS.includes(body.audioMethod)) cfg.audioMethod = body.audioMethod;
  if (typeof body.testNumber === 'string') cfg.testNumber = body.testNumber.trim().slice(0, 30);
  if (typeof body.blocklist === 'string') cfg.blocklist = body.blocklist.slice(0, 200000);
  await setKV('config', cfg);
  res.json({ ok: true, config: cfg });
}
