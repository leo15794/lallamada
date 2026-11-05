import { sql } from './_lib.js';

/** Chequeo público: solo indica si la función y la base de datos responden. */
export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  try {
    await sql`select 1`;
    res.json({ ok: true, db: true, password: Boolean(process.env.PANEL_PASSWORD) });
  } catch (e) {
    res.status(500).json({ ok: false, db: false, error: String(e.message || e).slice(0, 200) });
  }
}
