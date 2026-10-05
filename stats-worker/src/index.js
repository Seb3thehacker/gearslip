// Gearslip usage notes: opt-in phones POST one short note a day, and a private dashboard counts
// them. No IP address is stored, and the phone's random ID is kept only as a salted hash.

const MAX_BODY = 8 * 1024
const MAX_CARS = 10
const short = (v, n = 80) => (typeof v === 'string' ? v.slice(0, n) : null)

// The tables as schema.sql has them, plus the columns added since, so a database made by an
// older version catches up on its own. Each runs once per Worker instance; an ALTER whose
// column already exists fails, and that's fine.
const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS pings (day TEXT NOT NULL, uid TEXT NOT NULL, version TEXT NOT NULL,
     code INTEGER, build TEXT, android TEXT, os TEXT, phone TEXT, car_today INTEGER, PRIMARY KEY (day, uid))`,
  `CREATE TABLE IF NOT EXISTS cars (day TEXT NOT NULL, uid TEXT NOT NULL, name TEXT, car TEXT, year TEXT,
     make TEXT, model TEXT, protocol TEXT, result TEXT, screen TEXT, dpi INTEGER,
     PRIMARY KEY (day, uid, name, car, year, protocol, result))`,
  `CREATE INDEX IF NOT EXISTS pings_uid ON pings (uid)`,
  `CREATE TABLE IF NOT EXISTS downloads (day TEXT NOT NULL, tag TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (day, tag))`,
  `CREATE TABLE IF NOT EXISTS releases (tag TEXT PRIMARY KEY, prerelease INTEGER NOT NULL, published TEXT)`,
  // Each phone's latest details. Phones send them only when they change, so a field a note
  // leaves out keeps its last value.
  `CREATE TABLE IF NOT EXISTS phones (uid TEXT PRIMARY KEY, android TEXT, os TEXT, phone TEXT, updated TEXT)`,
]
const ADDED_COLUMNS = [
  `ALTER TABLE pings ADD COLUMN os TEXT`,
  `ALTER TABLE cars ADD COLUMN screen TEXT`,
  `ALTER TABLE cars ADD COLUMN dpi INTEGER`,
  // Older builds sent details in every note: carry the latest of those over once.
  `INSERT OR IGNORE INTO phones (uid, android, os, phone, updated)
     SELECT uid, android, os, phone, day FROM pings p
     WHERE day = (SELECT MAX(day) FROM pings q WHERE q.uid = p.uid)
       AND (android IS NOT NULL OR phone IS NOT NULL)`,
]
let schemaReady = null
function ensureSchema(env) {
  schemaReady ??= (async () => {
    for (const sql of SCHEMA) await env.DB.prepare(sql).run()
    for (const sql of ADDED_COLUMNS) await env.DB.prepare(sql).run().catch(() => {})
  })().catch((e) => { schemaReady = null; throw e })
  return schemaReady
}

export default {
  async fetch(request, env) {
    await ensureSchema(env)
    const url = new URL(request.url)
    if (request.method === 'POST' && url.pathname === '/ping') return ping(request, env)
    if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/api/summary')) {
      if (!env.DASHBOARD_KEY || url.searchParams.get('key') !== env.DASHBOARD_KEY) {
        return new Response('Not found', { status: 404 })
      }
      // The daily trigger normally saves today's counts; this covers a missed or late one.
      await snapshotDownloads(env, false).catch((e) => console.log('github:', e.message))
      const summary = await summarize(env)
      if (url.pathname === '/api/summary') return Response.json(summary)
      return new Response(dashboard(summary), { headers: { 'content-type': 'text/html; charset=utf-8' } })
    }
    return new Response('Not found', { status: 404 })
  },

  async scheduled(event, env) {
    await ensureSchema(env)
    await snapshotDownloads(env, true)
  },
}

// Saves each release's APK download count for today. GitHub only keeps a running total, so
// downloads per day come from the difference between days.
async function snapshotDownloads(env, force) {
  if (!env.GITHUB_REPO) return
  const day = today()
  // Today's counts and the release list both have to be there; either missing means fetch again.
  if (!force && (await env.DB.prepare(`SELECT 1 WHERE EXISTS (SELECT 1 FROM downloads WHERE day = ?)
                                        AND EXISTS (SELECT 1 FROM releases)`).bind(day).first())) return
  const headers = { 'user-agent': 'gearslip-stats', accept: 'application/vnd.github+json' }
  if (env.GITHUB_TOKEN) headers.authorization = `Bearer ${env.GITHUB_TOKEN}`
  const res = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}/releases?per_page=100`, { headers })
  if (!res.ok) throw new Error(`GitHub answered ${res.status}`)
  const statements = (await res.json()).filter((release) => !release.draft).flatMap((release) => {
    const count = release.assets.filter((a) => a.name.endsWith('.apk')).reduce((n, a) => n + a.download_count, 0)
    return [
      env.DB.prepare('INSERT OR REPLACE INTO downloads (day, tag, count) VALUES (?, ?, ?)').bind(day, release.tag_name, count),
      env.DB.prepare('INSERT OR REPLACE INTO releases (tag, prerelease, published) VALUES (?, ?, ?)')
        .bind(release.tag_name, release.prerelease ? 1 : 0, release.published_at),
    ]
  })
  if (statements.length) await env.DB.batch(statements)
}

const today = () => new Date().toISOString().slice(0, 10)
// Tags are "v0.1.10-alpha" or "0.0.05-alpha"; the app reports "0.1.10-alpha".
const versionOf = (tag) => tag.replace(/^v/, '')

async function ping(request, env) {
  const text = await request.text()
  if (text.length > MAX_BODY) return new Response('Too large', { status: 413 })
  let note
  try {
    note = JSON.parse(text)
  } catch {
    return new Response('Bad JSON', { status: 400 })
  }
  const id = short(note.id, 64)
  const version = short(note.version, 32)
  if (!id || !version) return new Response('Missing id or version', { status: 400 })

  const uid = await hash(`${env.SALT}:${id}`)
  const day = today()
  const android = short(note.android, 16), os = short(note.os, 16), phone = short(note.phone)
  const statements = [
    env.DB.prepare(
      `INSERT INTO pings (day, uid, version, code, build) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (day, uid) DO UPDATE SET version = excluded.version, code = excluded.code, build = excluded.build`,
    ).bind(day, uid, version, Number.isInteger(note.code) ? note.code : null, short(note.build, 16)),
  ]
  if (android || os || phone) {
    statements.push(
      env.DB.prepare(
        `INSERT INTO phones (uid, android, os, phone, updated) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (uid) DO UPDATE SET android = COALESCE(excluded.android, android),
           os = COALESCE(excluded.os, os), phone = COALESCE(excluded.phone, phone), updated = excluded.updated`,
      ).bind(uid, android, os, phone, day),
    )
  }
  for (const car of Array.isArray(note.cars) ? note.cars.slice(0, MAX_CARS) : []) {
    statements.push(
      env.DB.prepare(
        `INSERT OR IGNORE INTO cars (day, uid, name, car, year, make, model, protocol, result, screen, dpi)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(day, uid, short(car.name) ?? '', short(car.car) ?? '', short(car.year, 8) ?? '',
        short(car.make) ?? '', short(car.model) ?? '', short(car.protocol, 8) ?? '', short(car.result) ?? '',
        short(car.screen, 16), Number.isInteger(car.dpi) ? car.dpi : null),
    )
  }
  await env.DB.batch(statements)
  return new Response(null, { status: 204 })
}

async function hash(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].slice(0, 16).map((b) => b.toString(16).padStart(2, '0')).join('')
}

async function summarize(env) {
  const all = async (sql) => (await env.DB.prepare(sql).all()).results
  const one = async (sql) => (await env.DB.prepare(sql).first()) ?? {}
  const latest = await all(`SELECT d.tag, d.count, r.prerelease, r.published FROM downloads d
                             LEFT JOIN releases r ON r.tag = d.tag
                             WHERE d.day = (SELECT MAX(day) FROM downloads)`)
  const seen = Object.fromEntries((await all(`SELECT version, COUNT(DISTINCT uid) AS n FROM pings
                                               WHERE build = 'release' GROUP BY version`)).map((r) => [r.version, r.n]))
  // The newest full release, the one most people are sent to; pre-releases don't count.
  const newest = await one(`SELECT tag FROM releases WHERE prerelease = 0 ORDER BY published DESC LIMIT 1`)
  const latestRelease = newest.tag ? { tag: newest.tag, downloads: latest.find((r) => r.tag === newest.tag)?.count ?? 0 } : null
  const releases = latest.map((r) => ({
    tag: r.tag,
    kind: r.prerelease ? 'Pre-release' : r.prerelease === 0 ? 'Release' : '',
    published: (r.published ?? '').slice(0, 10),
    downloads: r.count,
    phones: seen[versionOf(r.tag)] ?? 0,
  }))
    .map((r) => ({ ...r, share: r.phones && r.downloads ? `${Math.round((100 * r.phones) / r.downloads)}%` : '' }))
    .sort((a, b) => b.published.localeCompare(a.published))
  // Only releases that have sent anything can say what share of people send; older ones predate the notes.
  const counted = releases.filter((r) => r.phones > 0)
  const countedDownloads = counted.reduce((n, r) => n + r.downloads, 0)
  const countedPhones = counted.reduce((n, r) => n + r.phones, 0)
  const totals = await all(`SELECT day, SUM(count) AS total FROM downloads GROUP BY day ORDER BY day`)
  const newPhones = Object.fromEntries((await all(`SELECT first_day AS day, COUNT(*) AS n FROM
                      (SELECT uid, MIN(day) AS first_day FROM pings WHERE build = 'release' GROUP BY uid)
                      GROUP BY first_day`)).map((r) => [r.day, r.n]))
  const perDay = totals.slice(1).map((r, i) => ({ day: r.day, downloads: r.total - totals[i].total, new_phones: newPhones[r.day] ?? 0 }))
  return {
    latestRelease,
    releases,
    totalDownloads: releases.reduce((n, r) => n + r.downloads, 0),
    share: countedDownloads ? Math.round((100 * countedPhones) / countedDownloads) : null,
    perDay: perDay.slice(-60).reverse(),
    daily: await all(`SELECT p.day, COUNT(*) AS phones,
                        SUM(EXISTS (SELECT 1 FROM cars c WHERE c.uid = p.uid AND c.day = p.day
                                    AND c.result LIKE 'connected%')) AS in_a_car,
                        SUM(p.day = f.first_day) AS new_phones
                      FROM pings p JOIN (SELECT uid, MIN(day) AS first_day FROM pings GROUP BY uid) f
                        ON f.uid = p.uid
                      WHERE p.day >= date('now', '-60 days') GROUP BY p.day ORDER BY p.day`),
    // Android runs no code at uninstall, so a phone that stopped sending is the nearest sign.
    new30: (await one(`SELECT COUNT(*) AS n FROM (SELECT uid, MIN(day) AS first_day FROM pings GROUP BY uid)
                       WHERE first_day >= date('now', '-30 days')`)).n ?? 0,
    quiet: (await one(`SELECT COUNT(*) AS n FROM (SELECT uid, MAX(day) AS last_day FROM pings GROUP BY uid)
                       WHERE last_day < date('now', '-14 days')`)).n ?? 0,
    everyone: (await one(`SELECT COUNT(DISTINCT uid) AS n FROM pings`)).n ?? 0,
    active7: (await one(`SELECT COUNT(DISTINCT uid) AS n FROM pings WHERE day >= date('now', '-7 days')`)).n ?? 0,
    active30: (await one(`SELECT COUNT(DISTINCT uid) AS n FROM pings WHERE day >= date('now', '-30 days')`)).n ?? 0,
    driving30: (await one(`SELECT COUNT(DISTINCT uid) AS n FROM cars
                           WHERE result LIKE 'connected%' AND day >= date('now', '-30 days')`)).n ?? 0,
    versions: await all(`SELECT version, COUNT(DISTINCT uid) AS phones FROM pings
                         WHERE day >= date('now', '-7 days') GROUP BY version ORDER BY phones DESC`),
    // Details come from each phone's latest, counted for phones that sent anything in 30 days.
    android: await all(`SELECT android, COUNT(*) AS phones FROM phones
                        WHERE android IS NOT NULL AND uid IN (SELECT uid FROM pings WHERE day >= date('now', '-30 days'))
                        GROUP BY android ORDER BY phones DESC`),
    screens: await all(`SELECT screen, COUNT(DISTINCT uid) AS phones FROM cars
                        WHERE screen IS NOT NULL GROUP BY screen ORDER BY phones DESC`),
    os: await all(`SELECT os, COUNT(*) AS phones FROM phones
                   WHERE os IS NOT NULL AND uid IN (SELECT uid FROM pings WHERE day >= date('now', '-30 days'))
                   GROUP BY os ORDER BY phones DESC`),
    phones: await all(`SELECT phone, COUNT(*) AS phones FROM phones
                       WHERE phone IS NOT NULL AND uid IN (SELECT uid FROM pings WHERE day >= date('now', '-30 days'))
                       GROUP BY phone ORDER BY phones DESC LIMIT 30`),
    cars: await all(`SELECT name, car, year, make, model, protocol,
                       GROUP_CONCAT(DISTINCT screen || CASE WHEN dpi THEN ' @ ' || dpi || ' dpi' ELSE '' END) AS screen,
                       SUM(result = 'connected') AS connected,
                       SUM(result != 'connected') AS failed,
                       GROUP_CONCAT(DISTINCT CASE WHEN result != 'connected' THEN result END) AS failures,
                       COUNT(DISTINCT uid) AS phones, MAX(day) AS last_seen
                     FROM cars GROUP BY name, car, year, make, model, protocol
                     ORDER BY phones DESC, connected DESC LIMIT 200`),
  }
}

const esc = (v) => String(v ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])

function table(rows, columns) {
  if (!rows.length) return '<p class="muted">Nothing yet.</p>'
  const head = columns.map(([, label]) => `<th>${esc(label)}</th>`).join('')
  const body = rows.map((r) => `<tr>${columns.map(([key]) => `<td>${esc(r[key])}</td>`).join('')}</tr>`).join('')
  return `<div class="scroll"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`
}

function chart(daily) {
  if (!daily.length) return '<p class="muted">No notes yet.</p>'
  const w = 600, h = 160, max = Math.max(...daily.map((d) => d.phones), 1), bw = w / daily.length
  const bars = daily.map((d, i) => {
    const ph = (d.phones / max) * (h - 20), ch = ((d.in_a_car ?? 0) / max) * (h - 20)
    return `<rect x="${i * bw + 1}" y="${h - ph}" width="${Math.max(bw - 2, 1)}" height="${ph}" class="all"><title>${esc(d.day)}: ${d.phones} phones</title></rect>` +
      `<rect x="${i * bw + 1}" y="${h - ch}" width="${Math.max(bw - 2, 1)}" height="${ch}" class="car"><title>${esc(d.day)}: ${d.in_a_car ?? 0} in a car</title></rect>`
  }).join('')
  return `<svg viewBox="0 0 ${w} ${h}" role="img" aria-label="Phones per day">${bars}</svg>
    <p class="muted"><span class="key all"></span> phones that day <span class="key car"></span> of those, in a car</p>`
}

function dashboard(s) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Gearslip usage</title>
<style>
:root { --bg:#fafafa; --fg:#1a1a1a; --muted:#666; --line:#ddd; --all:#9db4d6; --car:#2e7d32; }
@media (prefers-color-scheme: dark) { :root { --bg:#121212; --fg:#eee; --muted:#999; --line:#333; --all:#3d5a80; --car:#66bb6a; } }
body { background:var(--bg); color:var(--fg); font:16px/1.5 system-ui, sans-serif; margin:0 auto; padding:16px; max-width:960px; }
h1 { font-size:1.5rem; margin:0 0 4px; } h2 { font-size:1.1rem; margin:28px 0 8px; }
.muted { color:var(--muted); font-size:.9rem; }
.stats { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px, 1fr)); gap:12px; }
.stat.big { margin-bottom:12px; padding:20px; } .stat.big b { font-size:3.2rem; line-height:1.1; }
.stat { border:1px solid var(--line); border-radius:12px; padding:12px; } .stat b { display:block; font-size:1.8rem; }
svg { width:100%; height:auto; } rect.all { fill:var(--all); } rect.car { fill:var(--car); }
.key { display:inline-block; width:10px; height:10px; border-radius:2px; margin:0 4px 0 12px; }
.key.all { background:var(--all); } .key.car { background:var(--car); }
.scroll { overflow-x:auto; } table { border-collapse:collapse; width:100%; font-size:.9rem; }
th, td { text-align:left; padding:6px 8px; border-bottom:1px solid var(--line); white-space:nowrap; }
</style></head><body>
<h1>Gearslip usage</h1><p class="muted">From phones that opted in to daily notes.</p>
${s.latestRelease ? `<div class="stat big"><b>${s.latestRelease.downloads}</b>downloads of ${esc(s.latestRelease.tag)}, the latest release</div>` : ''}
<div class="stats">
  <div class="stat"><b>${s.active7}</b>phones, last 7 days</div>
  <div class="stat"><b>${s.active30}</b>phones, last 30 days</div>
  <div class="stat"><b>${s.driving30}</b>used in a car, last 30 days</div>
  <div class="stat"><b>${s.new30}</b>new phones, last 30 days</div>
  <div class="stat"><b>${s.quiet}</b>gone quiet (nothing in 14 days)</div>
  <div class="stat"><b>${s.everyone}</b>phones ever</div>
</div>
<p class="muted">Android tells an app nothing when it's uninstalled, so "gone quiet" is the nearest
sign: phones that sent notes before and stopped. Turning the notes off looks the same.</p>
<h2>Downloads and phones</h2>
<div class="stats">
  <div class="stat"><b>${s.totalDownloads}</b>APK downloads on GitHub, all releases</div>
  <div class="stat"><b>${s.share == null ? '–' : s.share + '%'}</b>of downloads send notes</div>
</div>
<p class="muted">The share counts only releases that have sent at least one note, and only release builds.
A download isn't always a new person: updating means downloading again, and some people download
twice. So read it as a rough guide, and multiply the phones above by about ${s.share ? Math.round(100 / s.share) : '–'} to estimate everyone.</p>
${table(s.releases, [['tag', 'Release'], ['kind', 'Type'], ['published', 'Published'], ['downloads', 'Downloads'], ['phones', 'Phones sending'], ['share', 'Share']])}
<h2>Downloads per day</h2>${s.perDay.length ? table(s.perDay, [['day', 'Day'], ['downloads', 'Downloads'], ['new_phones', 'New phones']])
  : '<p class="muted">Counts are saved once a day, so this fills in from tomorrow.</p>'}
<h2>Phones per day</h2>${chart(s.daily)}
<h2>New phones per day</h2>${table(s.daily.filter((d) => d.new_phones > 0).slice().reverse(), [['day', 'Day'], ['new_phones', 'New phones']])}
<h2>Cars</h2>${table(s.cars, [['name', 'Head unit'], ['car', 'Car'], ['year', 'Year'], ['make', 'Maker'], ['model', 'Unit model'],
    ['protocol', 'Protocol'], ['screen', 'Screen'], ['connected', 'Connected'], ['failed', 'Failed'], ['failures', 'Why it failed'], ['phones', 'Phones'], ['last_seen', 'Last seen']])}
<h2>Car screens</h2>${table(s.screens, [['screen', 'Usable size'], ['phones', 'Phones']])}
<h2>Versions, last 7 days</h2>${table(s.versions, [['version', 'Version'], ['phones', 'Phones']])}
<h2>Phones</h2>${table(s.phones, [['phone', 'Phone'], ['phones', 'Count']])}
<h2>GrapheneOS, last 30 days</h2>${table(s.os, [['os', 'System'], ['phones', 'Phones']])}
<h2>Android</h2>${table(s.android, [['android', 'Android'], ['phones', 'Count']])}
</body></html>`
}
