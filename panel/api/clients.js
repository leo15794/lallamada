import { sql, auth, parseClients, setKV } from './_lib.js';

export default async function handler(req, res) {
  if (!auth(req, res)) return;
  if (req.method === 'GET') {
    const rows = await sql`select phone, name from clients order by pos`;
    res.setHeader('Cache-Control', 'no-store');
    return res.json({ clients: rows });
  }
  if (req.method !== 'POST') return res.status(405).end();
  const list = parseClients((req.body && req.body.text) || '');
  if (!list.length) return res.status(400).json({ error: 'No encontré teléfonos en el archivo' });
  const pos = list.map((_, i) => i);
  const phones = list.map((c) => c.phone);
  const names = list.map((c) => c.name);
  await sql.transaction([
    sql`delete from clients`,
    sql`insert into clients (pos, phone, name)
        select * from unnest(${pos}::int[], ${phones}::text[], ${names}::text[])`,
  ]);
  await setKV('clientsVersion', Date.now());
  res.json({ ok: true, count: list.length });
}
