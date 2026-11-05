import { sql, auth, getKV, setKV, getConfig } from './_lib.js';

/** El celular llama acá cada pocos segundos: manda estado y resultados, recibe órdenes y versiones. */
export default async function handler(req, res) {
  if (!auth(req, res)) return;
  if (req.method !== 'POST') return res.status(405).end();
  const { status, results } = req.body || {};
  if (status) await setKV('device', { ...status, updatedAt: Date.now() });

  if (Array.isArray(results) && results.length) {
    const r = results.slice(0, 500);
    const col = (k) => r.map((x) => String(x[k] ?? '').slice(0, 1000));
    await sql`insert into results (ts, phone, name, result, detail)
              select * from unnest(${col('ts')}::text[], ${col('phone')}::text[], ${col('name')}::text[],
                                   ${col('result')}::text[], ${col('detail')}::text[])`;
  }

  const commands = await sql`update commands set delivered_at = now()
                             where delivered_at is null returning id, cmd, arg`;
  commands.sort((a, b) => Number(a.id) - Number(b.id));

  const [config, clientsVersion, audioVersion] = await Promise.all([
    getConfig(),
    getKV('clientsVersion', 0),
    getKV('audioVersion', 0),
  ]);
  res.setHeader('Cache-Control', 'no-store');
  res.json({ commands, config, clientsVersion, audioVersion });
}
