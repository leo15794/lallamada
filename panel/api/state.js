import { sql, auth, getKV, getConfig } from './_lib.js';

export default async function handler(req, res) {
  if (!auth(req, res)) return;
  const [config, device, clients, audio, results, stats, pending] = await Promise.all([
    getConfig(),
    getKV('device', null),
    sql`select count(*)::int as n from clients`,
    sql`select name, version, length(data)::int as size, updated_at from audio where id = 1`,
    sql`select ts, phone, name, result, detail from results order by id desc limit 100`,
    sql`select result, count(*)::int as n from results group by result`,
    sql`select id, cmd, arg, created_at from commands where delivered_at is null order by id`,
  ]);
  res.setHeader('Cache-Control', 'no-store');
  res.json({
    now: Date.now(),
    config,
    device,
    clients: clients[0].n,
    audio: audio[0] || null,
    results,
    stats,
    pending,
  });
}
