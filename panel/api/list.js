import { sql, auth } from './_lib.js';

/** Lista de clientes con el último resultado de cada uno. */
export default async function handler(req, res) {
  if (!auth(req, res)) return;
  const rows = await sql`
    select c.pos, c.phone, c.name, r.result, r.detail, r.ts
    from clients c
    left join lateral (
      select result, detail, ts from results where phone = c.phone order by id desc limit 1
    ) r on true
    order by c.pos
    limit 5000`;
  res.setHeader('Cache-Control', 'no-store');
  res.json({ clients: rows });
}
