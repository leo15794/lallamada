import { sql, auth, setKV } from './_lib.js';

export default async function handler(req, res) {
  if (!auth(req, res)) return;
  if (req.method === 'GET') {
    const r = await sql`select name, encode(data, 'base64') as b64 from audio where id = 1`;
    res.setHeader('Cache-Control', 'no-store');
    if (!r.length) return res.json({ name: '', b64: '' });
    return res.json({ name: r[0].name, b64: r[0].b64.replace(/\s/g, '') });
  }
  if (req.method !== 'POST') return res.status(405).end();
  const { name, b64 } = req.body || {};
  if (!b64) return res.status(400).json({ error: 'Falta el audio' });
  const buf = Buffer.from(b64, 'base64');
  if (buf.length > 3_200_000) return res.status(413).json({ error: 'El audio es muy grande (máximo 3 MB)' });
  const version = Date.now();
  const hex = buf.toString('hex');
  await sql`insert into audio (id, name, data, version, updated_at)
            values (1, ${String(name || 'audio').slice(0, 200)}, decode(${hex}, 'hex'), ${version}, now())
            on conflict (id) do update set name = excluded.name, data = excluded.data,
              version = excluded.version, updated_at = now()`;
  await setKV('audioVersion', version);
  res.json({ ok: true, size: buf.length });
}
