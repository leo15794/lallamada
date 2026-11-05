import { sql, auth } from './_lib.js';

const csv = (s) => '"' + String(s ?? '').replace(/"/g, "'") + '"';

export default async function handler(req, res) {
  if (!auth(req, res)) return;
  if (req.method === 'DELETE') {
    await sql`delete from results`;
    return res.json({ ok: true });
  }
  const rows = await sql`select ts, phone, name, result, detail from results order by id`;
  const lines = ['fecha,telefono,nombre,resultado,detalle'];
  for (const r of rows) lines.push([r.ts, r.phone, r.name, r.result, r.detail].map(csv).join(','));
  res.setHeader('Content-Type', 'text/csv; charset=utf-8');
  res.setHeader('Content-Disposition', 'attachment; filename="resultados.csv"');
  res.setHeader('Cache-Control', 'no-store');
  res.send('﻿' + lines.join('\n'));
}
