import { neon } from '@neondatabase/serverless';

export const sql = neon(process.env.DATABASE_URL);

export const DEFAULT_CONFIG = {
  ring: 35,
  pause: 20,
  hStart: 9,
  hEnd: 20,
  audioMethod: 'mtk',
  testNumber: '',
  blocklist: '',
  amdEnabled: false,
  amdOnMachine: 'cortar',
  amdThresholdDb: -45,
  amdInitialSilenceMs: 1500,
  amdSilenceEndMs: 700,
  amdMachineMs: 3000,
  amdMaxMs: 5000,
  answerDelayMs: 1000,
};

/** Verifica la contraseña (cabecera x-key). Responde 401 si no coincide. */
export function auth(req, res) {
  const key = String(req.headers['x-key'] || '');
  const pass = process.env.PANEL_PASSWORD || '';
  if (!pass || key !== pass) {
    res.status(401).json({ error: 'Contraseña incorrecta' });
    return false;
  }
  return true;
}

export async function getKV(key, def) {
  const r = await sql`select value from kv where key = ${key}`;
  return r.length ? r[0].value : def;
}

export async function setKV(key, value) {
  await sql`insert into kv (key, value, updated_at) values (${key}, ${JSON.stringify(value)}::jsonb, now())
            on conflict (key) do update set value = excluded.value, updated_at = now()`;
}

export async function getConfig() {
  return { ...DEFAULT_CONFIG, ...(await getKV('config', {})) };
}

/** Mismo criterio que la app: teléfono (>= 8 dígitos) y nombre, separados por coma, punto y coma o tab. */
export function parseClients(text) {
  const out = [];
  const seen = new Set();
  const clean = String(text).replace(/[\u0000\uFEFF]/g, '');
  for (const line of clean.split(/\r?\n/)) {
    let phone = null;
    let name = '';
    for (const raw of line.split(/[,;\t]/)) {
      const s = raw.trim().replace(/^"|"$/g, '').trim();
      if (!s) continue;
      const digits = s.replace(/\D/g, '');
      const looksLikePhone = digits.length >= 8 && /^[\d+\-(). ]+$/.test(s);
      if (phone === null && looksLikePhone) phone = (s.startsWith('+') ? '+' : '') + digits;
      else if (!name && digits.length === 0) name = s;
    }
    if (phone) {
      const k = phone.replace(/\D/g, '').slice(-8);
      if (!seen.has(k)) {
        seen.add(k);
        out.push({ phone, name });
      }
    }
  }
  return out;
}

/** Explica por qué un archivo no tiene teléfonos reconocibles. */
export function explainNoPhones(text) {
  const clean = String(text).replace(/[\u0000\uFEFF]/g, '');
  const lines = clean.split(/\r?\n/).filter((l) => l.trim()).slice(0, 3);
  let why = 'No encontré teléfonos (necesito al menos 8 dígitos por número).';
  if (/\d[.,]\d+E\+?\d+/i.test(clean)) {
    why = 'Excel convirtió los teléfonos a notación científica (por ejemplo 5,49E+12) y se perdieron los dígitos. ' +
      'En Excel poné la columna del teléfono con formato Texto, volvé a escribir/pegar los números y guardá de nuevo como CSV.';
  } else if (!lines.length) {
    why = 'El archivo está vacío.';
  }
  return why + (lines.length ? ' Primeras líneas recibidas: ' + lines.map((l) => '«' + l.slice(0, 60) + '»').join(' | ') : '');
}
