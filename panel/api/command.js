import { sql, auth } from './_lib.js';

const CMDS = ['start', 'stop', 'test', 'reset'];

export default async function handler(req, res) {
  if (!auth(req, res)) return;
  if (req.method !== 'POST') return res.status(405).end();
  const { cmd, arg } = req.body || {};
  if (!CMDS.includes(cmd)) return res.status(400).json({ error: 'Orden inválida' });
  if (cmd === 'stop') {
    // Detener anula cualquier orden pendiente de iniciar o probar.
    await sql`update commands set delivered_at = now() where delivered_at is null and cmd in ('start', 'test')`;
  }
  await sql`insert into commands (cmd, arg) values (${cmd}, ${String(arg || '').slice(0, 30)})`;
  res.json({ ok: true });
}
