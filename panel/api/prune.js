import { sql, auth, setKV } from './_lib.js';

const MODES = {
  all: null, // cualquier resultado
  answered: ['atendió'],
  answered_vm: ['atendió', 'buzón'],
};

/** Quita de la lista a los clientes que ya fueron llamados (según el modo) y renumera el resto. */
export default async function handler(req, res) {
  if (!auth(req, res)) return;
  if (req.method !== 'POST') return res.status(405).end();
  const mode = (req.body && req.body.mode) || 'all';
  if (!(mode in MODES)) return res.status(400).json({ error: 'Modo inválido' });
  const results = MODES[mode];

  const before = await sql`select count(*)::int as n from clients`;
  const keep = results
    ? await sql`select c.phone, c.name from clients c
                where not exists (select 1 from results r where r.phone = c.phone and r.result = any(${results}::text[]))
                order by c.pos`
    : await sql`select c.phone, c.name from clients c
                where not exists (select 1 from results r where r.phone = c.phone)
                order by c.pos`;

  const removed = before[0].n - keep.length;
  if (removed === 0) return res.json({ ok: true, removed: 0, remaining: keep.length });

  const pos = keep.map((_, i) => i);
  const phones = keep.map((c) => c.phone);
  const names = keep.map((c) => c.name);
  await sql.transaction([
    sql`delete from clients`,
    sql`insert into clients (pos, phone, name)
        select * from unnest(${pos}::int[], ${phones}::text[], ${names}::text[])`,
  ]);
  await setKV('clientsVersion', Date.now());
  res.json({ ok: true, removed, remaining: keep.length });
}
