/*
 * Injected into the in-app browser. Everything here runs inside the user's
 * logged-in UKC session, so fetches carry their cookies and the Cloudflare
 * clearance the WebView has already earned.
 */
(() => {
  if (window.__ukcReady) return;
  window.__ukcReady = true;

  const ORIGIN = 'https://www.ukclimbing.com';
  const clean = (v) => (v || '').replace(/\s+/g, ' ').trim();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  /*
   * Pacing. A pool of workers each pausing exactly 250ms produces a metronome:
   * six requests, a gap, six requests, hour after hour. That is the shape a
   * rate limiter is looking for, and a long import kept dying part way through
   * rather than being told to slow down. So every wait is scattered, every so
   * often a worker takes a longer breather, and when one worker does get
   * throttled they all stand back together instead of taking turns being
   * refused.
   */

  /** A wait that is never twice the same: 55% to 145% of what was asked. */
  const jitter = (ms) => sleep(Math.round(ms * (0.55 + Math.random() * 0.9)));

  /** Roughly one page in twelve, a much longer pause. */
  function breather(ms) {
    if (Math.random() > 0.08) return Promise.resolve();
    return sleep(Math.round(ms * (4 + Math.random() * 4)));
  }

  const KNOWN_TYPES = [
    'Bouldering', 'Boulder', 'Trad', 'Sport', 'Winter', 'Aid',
    'Alpine', 'Ice', 'Mixed', 'Via Ferrata', 'Scrambling',
  ];

  function attributeText(cell) {
    if (!cell) return '';
    const values = [];
    for (const el of cell.querySelectorAll('*')) {
      for (const name of ['title', 'alt', 'aria-label', 'data-original-title', 'data-title']) {
        const value = clean(el.getAttribute(name));
        if (value && !values.includes(value)) values.push(value);
      }
    }
    return values.join(' ');
  }

  function extractType(cell) {
    if (!cell) return '';
    const combined = clean(cell.textContent) + ' ' + attributeText(cell);
    for (const type of KNOWN_TYPES) {
      if (new RegExp('\\b' + type + '\\b', 'i').test(combined)) {
        return type === 'Boulder' ? 'Bouldering' : type;
      }
    }
    return clean(cell.textContent).replace(/^[–-]+|[–-]+$/g, '');
  }

  function extractStars(cell) {
    if (!cell) return 0;
    let count = (clean(cell.textContent).match(/★/g) || []).length;
    if (count === 0) {
      const meta = attributeText(cell);
      const m = meta.match(/([0-3])\s*stars?/i);
      count = m ? parseInt(m[1], 10) : (meta.match(/\bstar\b/gi) || []).length;
    }
    return Math.min(count, 3);
  }

  function parseButtresses(html) {
    const m = html.match(/\b(?:let|var|const)\s+buttresses\s*=\s*\[/i);
    if (!m) return {};
    const start = m.index + m[0].length - 1;
    let depth = 0, end = null;
    for (let i = start; i < html.length; i++) {
      if (html[i] === '[') depth++;
      else if (html[i] === ']') depth--;
      if (depth === 0) { end = i + 1; break; }
    }
    if (end === null) return {};
    let parsed;
    try { parsed = JSON.parse(html.slice(start, end)); } catch (e) { return {}; }
    const out = {};
    for (const item of parsed) {
      if (Array.isArray(item) && item.length >= 3 &&
          typeof item[0] === 'number' && typeof item[1] === 'number' &&
          typeof item[2] === 'string') {
        out[clean(item[2])] = [item[0], item[1]];
      }
    }
    return out;
  }

  function findRoutesTable(doc) {
    for (const table of doc.querySelectorAll('table')) {
      for (const row of table.querySelectorAll('tr')) {
        const cells = [...row.children].filter((c) => /^(TH|TD)$/.test(c.tagName));
        const lowered = cells.map((c) => clean(c.textContent).toLowerCase());
        if (['name', 'grade', 'logs'].every((k) => lowered.includes(k))) {
          return { table, headerRow: row, headings: cells.map((c) => clean(c.textContent)) };
        }
      }
    }
    return null;
  }

  function parseCrag(doc, html, sourceUrl) {
    const found = findRoutesTable(doc);
    if (!found) return null;

    const { table, headerRow, headings } = found;
    const index = {};
    headings.forEach((h, i) => { if (h) index[h.toLowerCase()] = i; });

    const h1 = [...doc.querySelectorAll('h1')]
      .find((h) => !(h.className || '').includes('sr-only'));
    const area = clean(h1 ? h1.textContent : doc.title)
      .replace(/^UKC Logbook\s*[-–—]\s*/i, '');

    const lat = doc.querySelector('meta[property="place:location:latitude"]');
    const lon = doc.querySelector('meta[property="place:location:longitude"]');
    const pins = parseButtresses(html);

    const sectors = [];
    const byName = {};
    let sector = '', sectorLat = null, sectorLon = null, count = 0;

    const rows = [...table.querySelectorAll('tr')];

    for (const row of rows.slice(rows.indexOf(headerRow) + 1)) {
      const cells = [...row.children].filter((c) => /^(TH|TD)$/.test(c.tagName));
      if (!cells.length) continue;

      const span = parseInt(cells[0].getAttribute('colspan') || '0', 10);

      if (cells.length === 1 || span > 1) {
        const h5 = cells[0].querySelector('h5');
        let text = clean(h5 ? h5.textContent : cells[0].textContent);
        if (!['', 'name', 'add missing', 'add missing climb'].includes(text.toLowerCase())) {
          text = text.replace(/\s+routes(\s+.*)?$/i, '');
          sector = text;
          const pin = pins[text];
          sectorLat = pin ? pin[0] : null;
          sectorLon = pin ? pin[1] : null;
        }
        continue;
      }

      const at = (label) => {
        const i = index[label];
        return (i == null || i >= cells.length) ? null : cells[i];
      };

      const nameCell = at('name'), gradeCell = at('grade'), logsCell = at('logs');
      if (!nameCell || !gradeCell || !logsCell) continue;

      const anchor = nameCell.querySelector('a[href]');
      const logs = clean(logsCell.textContent).match(/\d[\d,]*/);
      if (!anchor || !clean(nameCell.textContent) || !logs) continue;

      let href = anchor.getAttribute('href');
      if (href.charAt(0) === '/') href = ORIGIN + href;
      if (href.replace(/\/$/, '') === sourceUrl.replace(/\/$/, '')) continue;

      if (!byName[sector]) {
        byName[sector] = { name: sector, latitude: sectorLat, longitude: sectorLon, routes: [] };
        sectors.push(byName[sector]);
      }

      byName[sector].routes.push({
        name: clean(nameCell.textContent),
        grade: clean(gradeCell.textContent),
        type: extractType(at('type')),
        stars: extractStars(at('stars')),
        logs: parseInt(logs[0].replace(/,/g, ''), 10),
        url: href,
      });

      count++;
    }

    if (!count) return null;

    return {
      area,
      source_url: sourceUrl,
      latitude: lat ? parseFloat(lat.content) : null,
      longitude: lon ? parseFloat(lon.content) : null,
      route_count: count,
      sectors,
    };
  }

  function resultRows() {
    const tables = [...document.querySelectorAll('table')]
      .sort((a, b) => b.querySelectorAll('tr').length - a.querySelectorAll('tr').length);
    if (!tables.length) return [];

    const out = [];
    for (const row of [...tables[0].querySelectorAll('tr')].slice(1)) {
      const a = row.querySelector('a[href*="/logbook/crags/"]');
      if (!a) continue;
      let href = a.getAttribute('href');
      if (href.charAt(0) === '/') href = ORIGIN + href;
      if (!/-\d+\/?$/.test(href)) continue;

      // The row carries "Routes: N", so crags with none can be skipped
      // without paying for a page load that will find nothing.
      const routes = clean(row.textContent).match(/Routes:\s*(\d[\d,]*)/i);

      out.push({
        name: clean(a.textContent),
        url: href,
        routes: routes ? parseInt(routes[1].replace(/,/g, ''), 10) : null,
      });
    }
    return out;
  }

  /** Tells the app what this page offers, so it can label its one button. */
  window.__ukcPageKind = function () {
    const isCrag = !!findRoutesTable(document);
    const results = isCrag ? [] : resultRows();
    return JSON.stringify({
      kind: isCrag ? 'crag' : (results.length ? 'results' : 'other'),
      count: results.length,
      title: clean(document.title),
    });
  };

  window.__ukcImportCurrent = async function () {
    try {
      const url = String(document.location.href).split('#')[0];
      const html = document.documentElement.outerHTML;
      const parsed = await parseCragData(html, url);
      const crag = (parsed && !parsed.empty) ? parsed : parseCrag(document, html, url);
      if (!crag) { Android.failed('no climbs on this page'); return; }
      Android.saveCrag(JSON.stringify(crag));
      Android.finished(1, 0);
    } catch (e) {
      Android.failed(String(e).slice(0, 120));
    }
  };

  /*
   * UKC swaps search results in by AJAX with no page load, so the app can't
   * rely on onPageFinished. Watch the DOM and push the page kind instead.
   */
  let lastKind = '';

  function notifyKind() {
    try {
      const kind = window.__ukcPageKind();
      if (kind !== lastKind) {
        lastKind = kind;
        Android.kind(kind);
      }
    } catch (e) { /* bridge not ready yet */ }
  }

  let timer = null;

  new MutationObserver(() => {
    clearTimeout(timer);
    timer = setTimeout(notifyKind, 400);
  }).observe(document.documentElement, { childList: true, subtree: true });

  setTimeout(notifyKind, 100);

  const cragIdOf = (url) => (url.match(/-(\d+)\/?$/) || [])[1] || url;

  // A crag page builds its table in ~200ms; anything slower is a dud page.
  const LOAD_TIMEOUT_MS = 4000;

  /** The most any one crag may take, whichever way it is being read. */
  const CRAG_TIMEOUT_MS = 20000;

  /** Cloudflare and UKC both answer a throttle with these. */
  function isThrottled(html) {
    return /just a moment|checking your browser|verify you are human|too many requests/i
      .test(html.slice(0, 4000));
  }

  /*
   * The climbs are not really in the HTML at all. UKC ships them as JSON in an
   * inline script (table_data / buttress_data / grade_list) and renders the
   * table client side. That script is only served on a real navigation, which
   * is why fetch() came back with a header row and nothing under it.
   *
   * A sandboxed iframe performs a navigation but runs no scripts, so it hands
   * back exactly that payload cheaply — no jQuery, no DataTables, no rendering.
   */
  function navigate(url, timeoutMs) {
    return new Promise((resolve) => {
      const frame = document.createElement('iframe');
      frame.setAttribute('sandbox', 'allow-same-origin');
      // One pixel, off screen. The payload is in the markup, so nothing here
      // needs laying out or painting — and at 800x600 the browser was doing
      // both, six pages at a time, for nobody's benefit.
      frame.style.cssText =
        'position:absolute;left:-10000px;top:0;width:1px;height:1px;border:0;';

      let settled = false;

      const finish = (value) => {
        if (settled) return;
        settled = true;
        clearTimeout(guard);
        try { frame.remove(); } catch (e) { /* already gone */ }
        resolve(value);
      };

      const guard = setTimeout(() => finish({ error: 'timeout' }), timeoutMs);

      frame.onerror = () => finish({ error: 'load error' });

      frame.onload = () => {
        let doc = null;
        try { doc = frame.contentDocument; } catch (e) { doc = null; }
        if (!doc) { finish({ error: 'no document' }); return; }

        finish({ html: doc.documentElement.outerHTML, doc });
      };

      frame.src = url;
      document.body.appendChild(frame);
    });
  }

  /** Pulls `name = [...]` or `name = {...}` out of a script by brace matching. */
  function literal(src, name, open) {
    const close = open === '[' ? ']' : '}';
    const match = src.match(new RegExp('\\b' + name + '\\s*=\\s*\\' + open));
    if (!match) return null;

    const start = match.index + match[0].length - 1;
    let depth = 0;
    let inString = false;
    let escaped = false;

    for (let i = start; i < src.length; i++) {
      const ch = src[i];

      // Descriptions contain brackets and quotes, so track string state.
      if (inString) {
        if (escaped) escaped = false;
        else if (ch === '\\') escaped = true;
        else if (ch === '"') inString = false;
        continue;
      }

      if (ch === '"') inString = true;
      else if (ch === open) depth++;
      else if (ch === close) {
        depth--;
        if (depth === 0) {
          try { return JSON.parse(src.slice(start, i + 1)); } catch (e) { return null; }
        }
      }
    }

    return null;
  }

  const scalar = (src, name) => {
    const m = src.match(new RegExp('\\b' + name + "\\s*=\\s*'([^']*)'"));
    return m ? m[1] : null;
  };

  const number = (src, name) => {
    const m = src.match(new RegExp('\\b' + name + '\\s*=\\s*(-?[\\d.]+)'));
    return m ? parseFloat(m[1]) : null;
  };

  /** Marker for a crag whose payload is present but holds no climbs. */
  const EMPTY = { empty: true };

  /** UKC's grade type for a tor summit, which is not a climb. */
  const SUMMIT = 'Summit';

  const decoder = document.createElement('textarea');

  /*
   * Names in the payload arrive HTML-encoded, sometimes twice, and some carry
   * markup: "Buckfastleigh <small>Buckfast</small>". Decode, drop the <small>
   * annotation with its contents, then strip anything else left over.
   */
  function text(raw) {
    let value = String(raw || '');

    for (let pass = 0; pass < 2 && /&(?:[a-z]+|#\d+);/i.test(value); pass++) {
      decoder.innerHTML = value;
      value = decoder.value;
    }

    return clean(
      value
        .replace(/<small\b[^>]*>[\s\S]*?<\/small>/gi, ' ')
        .replace(/<[^>]*>/g, ' ')
    );
  }

  /*
   * Same decoding as text(), but for prose: paragraph and line breaks are
   * kept, since a crag's approach notes read as a list of steps.
   */
  function prose(raw) {
    let value = String(raw || '');

    for (let pass = 0; pass < 2 && /&(?:[a-z]+|#\d+);/i.test(value); pass++) {
      decoder.innerHTML = value;
      value = decoder.value;
    }

    return value
      // Comments first: UKC hides the "More..." control in one, and it holds
      // markup, so stripping tags alone leaves "More...-->" behind.
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/(?:p|div|li)>/gi, '\n\n')
      .replace(/<[^>]*>/g, ' ')
      .replace(/[ \t]+/g, ' ')
      .replace(/ *\n */g, '\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }

  /*
   * The crag's own words: UKC renders "Crag features" and "Approach notes"
   * into the page itself, so they cost nothing extra to keep.
   */
  function cragNotes(html) {
    let doc;
    try {
      doc = new DOMParser().parseFromString(html, 'text/html');
    } catch (e) {
      return '';
    }

    const parts = [];

    for (const id of ['features_info', 'approach_info']) {
      const box = doc.getElementById(id);
      if (!box) continue;

      // The heading is the section's own title, so keep it as one.
      const heading = box.querySelector('h1,h2,h3,h4,h5');
      const title = heading ? clean(heading.textContent) : '';
      if (heading) heading.remove();

      // "Read more" and similar controls are page furniture, not prose.
      box.querySelectorAll('a[href="#"],button,script,style').forEach((n) => n.remove());
      box.querySelectorAll('a,span').forEach((n) => {
        if (/^[›»>\s]*(more|read more|less|show less)[›»>\s]*$/i.test(n.textContent || '')) {
          n.remove();
        }
      });

      const body = prose(box.innerHTML)
        .replace(/[\s›»>.]*\b(?:read\s+|show\s+)?more\b\s*\.{0,3}\s*[›»>-]*$/i, '')
        .trim();

      if (body) parts.push(title ? title + '\n' + body : body);
    }

    return parts.join('\n\n');
  }

  /** Builds the export straight from UKC's own JSON. */
  async function parseCragData(html, sourceUrl) {
    const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)]
      .map((m) => m[1]);
    const src = scripts.find((s) => /\btable_data\s*=\s*\[/.test(s));
    if (!src) return null;

    const climbs = literal(src, 'table_data', '[');
    if (!climbs) return null;

    // A crag can carry the payload and still list nothing, which is not a failure.
    if (!climbs.length) return EMPTY;

    const buttressData = literal(src, 'buttress_data', '{') || {};
    const order = literal(src, 'buttress_order', '{') || {};
    const grades = literal(src, 'grade_list', '{') || {};
    const types = literal(src, 'grade_type_list', '{') || {};

    // A "Summit" is a tor's trig point, not a climb. Drop them, and drop the
    // crag entirely when that is all it had.
    const climbing = climbs.filter((c) => types[c.gradetype] !== SUMMIT);
    if (!climbing.length) return EMPTY;

    const base = scalar(src, 'base_url') || new URL(sourceUrl).pathname;
    const pins = parseButtresses(html);

    // Crag name: the og:title is stable, the h1 only exists once rendered.
    const title = html.match(
      /<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']/i
    );
    const area = text(title ? title[1] : '')
      .replace(/^UKC Logbook\s*[-–—]\s*/i, '');

    const gradeText = (climb) => {
      const table = grades[climb.gradetype] || {};
      const entry = table[climb.grade];
      const parts = [];
      if (entry && entry.name && entry.name !== 'summit') parts.push(entry.name);
      if (climb.techgrade) parts.push(climb.techgrade);
      return parts.join(' ');
    };

    // buttress_order maps display position to buttress id.
    const rank = {};
    for (const key of Object.keys(order)) rank[order[key]] = parseInt(key, 10);

    const groups = new Map();

    for (const climb of climbing) {
      const id = climb.buttress_id;
      if (!groups.has(id)) groups.set(id, []);
      groups.get(id).push(climb);
    }

    const buttresses = [...groups.keys()]
      .sort((a, b) => (rank[a] == null ? 1e9 : rank[a]) - (rank[b] == null ? 1e9 : rank[b]))
      .map((id) => {
        const info = buttressData[id] || {};
        const name = text(info.name);
        const pin = pins[name];

        const list = groups.get(id)
          .slice()
          .sort((a, b) => (a.climb_ordering || 0) - (b.climb_ordering || 0))
          .map((climb) => ({
            name: text(climb.name),
            grade: gradeText(climb),
            type: types[climb.gradetype] || '',
            stars: Math.min(parseInt(climb.stars, 10) || 0, 3),
            logs: parseInt(climb.logs, 10) || 0,
            url: ORIGIN + base + climb.slug,
            // Kept rather than re-derived from the URL: it keys the topo lines
            // and UKC's own logging page.
            climb_id: parseInt(climb.id, 10) || 0,
            // The crag page carries every climb's description already, so no
            // climb ever needs a page of its own.
            description: prose(climb.desc),
            // Only the count: the photos themselves stay on UKC.
            photos: parseInt(climb.n_photos, 10) || 0,
            height: parseInt(climb.height, 10) || 0,
            pitches: parseInt(climb.pitches, 10) || 0,
            /*
             * Signed in, the crag page already knows what the reader has done
             * here: a tick for an ascent, a cross for an attempt. Exact, keyed
             * by id, and free — no logbook request needed for these crags.
             */
            ticked: /fa-check/.test(String(climb.ascents_css || '')),
            attempted: /fa-times/.test(String(climb.ascents_css || '')),
            // UKC's own numeric difficulty, the only way to order grades
            // across systems (font, British trad, sport) in one list.
            grade_score: parseFloat(climb.gradescore) || 0,
          }));

        return {
          name,
          latitude: pin ? pin[0] : null,
          longitude: pin ? pin[1] : null,
          routes: list,
        };
      });

    // Each climb says whether it appears on a topo, so crags with none
    // never pay for the extra request.
    const cragId = number(src, 'cragId');
    let topos = [];

    if (climbing.some((c) => c.has_topo)) {
      try { topos = await loadTopos(src, cragId); } catch (e) { topos = []; }
    }

    return {
      area,
      source_url: sourceUrl,
      latitude: number(src, 'lat'),
      longitude: number(src, 'lng'),
      route_count: climbing.length,
      description: cragNotes(html),
      sectors: buttresses,
      topos,
    };
  }

  /*
   * Topos are not in the crag page. The page POSTs its id plus the `auth`
   * token it carries to crag_topo.php, which answers with markup holding an
   * `all_topos` object: one entry per topo, each with the photo's id and
   * natural size, and a `climbs` map of climb id to name and line_data (the
   * polyline drawn over the photo, in image pixel coordinates).
   */
  /*
   * The photo lives on a different host behind a signed, short-lived URL.
   * Reading it here and drawing it to a canvas taints the canvas, so
   * toDataURL throws and nothing gets saved. Hand the link straight to the
   * app instead, which downloads it with the session's cookies.
   */
  function cacheTopoImage(topo) {
    const source = topo.image || topo.thumb;
    if (!source) return false;

    return Android.fetchTopoImage(String(topo.topo_id), String(source));
  }

  async function loadTopos(html, cragId) {
    const auth = scalar(html, 'auth');
    if (!auth || !cragId) return [];

    const body = new URLSearchParams();
    body.set('id', String(cragId));
    body.set('auth', auth);

    const response = await fetch(ORIGIN + '/logbook/crag_topo.php', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'X-Requested-With': 'XMLHttpRequest',
      },
      body,
    });

    if (!response.ok) return [];

    const markup = await response.text();
    const all = literal(markup, 'all_topos', '{');
    if (!all) return [];

    const order = literal(markup, 'topo_order', '[') || Object.keys(all);
    const seen = new Set();
    const topos = [];

    for (const key of order.concat(Object.keys(all))) {
      const topo = all[key];
      if (!topo || seen.has(String(key))) continue;
      seen.add(String(key));

      const lines = [];

      for (const climbId of Object.keys(topo.climbs || {})) {
        const climb = topo.climbs[climbId];
        const points = Array.isArray(climb.line_data)
          ? climb.line_data
          : parsePoints(climb.line_data);

        if (!points.length) continue;

        lines.push({
          climb_id: parseInt(climbId, 10),
          name: text(climb.climb_name),
          points,
        });
      }

      // The photo sits behind a signed, expiring URL, so storing the link
      // would be useless later. Cache the pixels now instead, every time:
      // whatever is on disk may be older than what UKC now serves.
      try { cacheTopoImage(topo); } catch (e) { /* keep the lines anyway */ }

      topos.push({
        topo_id: topo.topo_id,
        image_id: topo.image_id,
        buttress: text(topo.buttress_name),
        // The photo as stored. TopoView knows how the coordinates relate to it.
        width: topo.image_x || 0,
        height: topo.image_y || 0,
        lines,
      });
    }

    return topos;
  }

  /** line_data is sometimes a JSON string rather than an array. */
  function parsePoints(raw) {
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
      return [];
    }
  }

  /**
   * A fetch that cannot hang.
   *
   * Without this a dropped connection — a VPN turning over, a train tunnel —
   * left the promise unsettled for ever. Six workers each holding one is a run
   * that has stopped without failing, which is exactly what it looked like: no
   * error, no progress, nothing in the log.
   */
  async function fetchWithin(url, timeoutMs) {
    const control = new AbortController();
    const guard = setTimeout(() => control.abort(), timeoutMs);

    try {
      return await fetch(url, { credentials: 'include', signal: control.signal });
    } finally {
      clearTimeout(guard);
    }
  }

  /** Loads one crag and turns it into an export, or explains why it could not. */
  async function loadCrag(url, timeoutMs) {
    // A plain fetch usually carries the payload and costs far less than a
    // navigation, so try it first and keep the iframe as the fallback.
    try {
      const response = await fetchWithin(url, timeoutMs);
      const html = await response.text();

      if (isThrottled(html)) return { throttled: true };

      const quick = await parseCragData(html, url);
      if (quick === EMPTY) return { empty: true };
      if (quick) return { crag: quick };
    } catch (e) { /* fall through to a real navigation */ }

    const page = await navigate(url, timeoutMs);
    if (page.error) return { error: page.error };

    if (isThrottled(page.html)) return { throttled: true };

    const crag = await parseCragData(page.html, url);
    if (crag === EMPTY) return { empty: true };
    if (crag) return { crag };

    // Fall back to the rendered table, in case a page ships no JSON payload.
    const scraped = parseCrag(page.doc, page.html, url);
    if (scraped) return { crag: scraped };

    const hasScript = /\btable_data\s*=/.test(page.html);
    return {
      crag: null,
      note: 'len=' + page.html.length + ' payload=' + (hasScript ? 'y' : 'n'),
    };
  }

  /** UKC only sets a non-zero userID once the session is signed in. */
  function userIdIn(html) {
    const m = html.match(/\buserID\s*=\s*(\d+)/);
    const id = m ? parseInt(m[1], 10) : 0;
    return id > 0 ? id : 0;
  }

  /*
   * Every climb page carries its own "Add to Logbook" submit, which POSTs to
   * addlogs.php. addlogs.php on its own has nothing to add, and a GET tells it
   * nothing, so the climb's own page is the only way in.
   *
   * This just finds the button and scrolls to it. The press stays the user's.
   */
  window.__ukcPrepareLog = function () {
    const button = document.getElementById('addToLogbookButton');
    if (!button) return JSON.stringify({ ready: false });

    try { button.scrollIntoView({ block: 'center' }); } catch (e) { /* older WebView */ }

    return JSON.stringify({ ready: true });
  };

  /*
   * Presses UKC's own "Add to Logbook", which POSTs the ticked climbs to
   * addlogs.php and lands on their form. The user asked for this by pressing
   * the app's button; nothing here saves a log on its own.
   */
  window.__ukcSubmitLog = function () {
    const button = document.getElementById('addToLogbookButton');
    if (!button) return JSON.stringify({ ready: false });

    button.click();
    return JSON.stringify({ ready: true });
  };

  window.__ukcSignedIn = function () {
    return JSON.stringify({ userId: userIdIn(document.documentElement.outerHTML) });
  };

  /*
   * Ticks live in the user's logbook, which pages 100 climbs at a time. Each
   * entry links to the climb, and those links are exactly the URLs the imported
   * crags use, so a tick is just a URL match.
   */
  /** Splits CSV text, honouring quoted fields with commas or newlines in them. */
  function parseCsv(text) {
    const rows = [];
    let row = [], field = '', quoted = false;

    for (let i = 0; i < text.length; i++) {
      const c = text[i];

      if (quoted) {
        if (c === '"') {
          if (text[i + 1] === '"') { field += '"'; i++; } else { quoted = false; }
        } else {
          field += c;
        }
        continue;
      }

      if (c === '"') { quoted = true; }
      else if (c === ',') { row.push(field); field = ''; }
      else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
      else if (c !== '\r') { field += c; }
    }

    if (field.length || row.length) { row.push(field); rows.push(row); }

    return rows;
  }

  /*
   * The whole logbook in one request. UKC's CSV export carries every ascent
   * with its climb and crag name, which beats walking showlog.php a hundred
   * rows at a time. It carries no ids, so the app matches on names.
   */
  async function logbookCsv() {
    let text = null;

    // Straight after a big import the connection is often still being punished,
    // and the first request comes back as a network error rather than a page.
    for (let attempt = 0; attempt < 3 && text === null; attempt++) {
      if (attempt) await sleep(2000 * attempt);

      try {
        const response = await fetch(ORIGIN + '/logbook/export/logbook_dlog_csv.php', {
          credentials: 'include',
        });

        if (response.ok) text = await response.text();
      } catch (e) { /* try again, then fall back to walking the pages */ }
    }

    if (text === null) return null;

    // Signed out, UKC answers with a page rather than a file.
    if (/^\s*<(!doctype|html)/i.test(text)) return null;

    const rows = parseCsv(text);
    if (rows.length < 2) return null;

    const header = rows[0].map((h) => h.trim().toLowerCase());
    const nameAt = header.indexOf('name');
    const cragAt = header.indexOf('crag');
    if (nameAt < 0 || cragAt < 0) return null;

    const seen = new Set();
    const out = [];

    for (const row of rows.slice(1)) {
      const name = clean(row[nameAt]);
      const crag = clean(row[cragAt]);
      if (!name || !crag) continue;

      const key = crag + '\u0000' + name;
      if (seen.has(key)) continue;

      seen.add(key);
      out.push({ crag, name });
    }

    return out;
  }

  /** Walks showlog.php for climb links: slower, but it yields exact URLs. */
  async function logbookPages(userId) {
    const found = new Set();

    for (let page = 1; page <= 50; page++) {
      const url = ORIGIN + '/logbook/showlog.php?id=' + userId +
        '&nresults=100&pg=' + page;
      const loaded = await navigate(url, 20000);
      if (!loaded.doc) break;

      const links = [...loaded.doc.querySelectorAll('a[href*="/logbook/crags/"]')]
        .map((a) => a.getAttribute('href'))
        .filter((h) => /\/logbook\/crags\/[^/]+\/[^/]+-\d+\/?$/.test(h));

      const before = found.size;
      for (const href of links) {
        found.add(href.charAt(0) === '/' ? ORIGIN + href : href);
      }

      Android.ticksProgress(found.size);

      const more = [...loaded.doc.querySelectorAll('a')]
        .some((a) => /next \d+ climbs/i.test(a.textContent || ''));

      if (!more || found.size === before) break;

      await sleep(400);
    }

    return [...found];
  }

  /*
   * The wishlist is a plain page of climb links, so it needs no matching:
   * the hrefs are the same URLs the imported crags carry.
   */
  async function wishlist(userId) {
    if (!userId) return null;

    try {
      const response = await fetch(ORIGIN + '/logbook/showlist.php?id=' + userId, {
        credentials: 'include',
      });

      if (!response.ok) return null;

      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');

      return [...doc.querySelectorAll('a[href*="/logbook/crags/"]')]
        .map((a) => a.getAttribute('href'))
        .filter((h) => /\/logbook\/crags\/[^/]+\/[^/]+-\d+\/?$/.test(h))
        .map((h) => (h.charAt(0) === '/' ? ORIGIN + h : h));
    } catch (e) {
      return null;
    }
  }

  /*
   * Ticklists are pages of climb links, the reader's own and any they
   * subscribe to. Each list costs a request, so this is only run alongside a
   * logbook sync rather than on its own.
   */
  async function ticklists(userId) {
    if (!userId) return null;

    try {
      const index = await fetch(ORIGIN + '/logbook/showticklists.php?id=' + userId, {
        credentials: 'include',
      });

      if (!index.ok) return null;

      const doc = new DOMParser().parseFromString(await index.text(), 'text/html');

      /*
       * The index links each list as set.php?id=N, twice over: once on its
       * thumbnail, which has no text, and once on its title. Keep the named
       * one. The pretty /logbook/ticklists/<slug>-<id> form redirects to the
       * same place, so it is matched too.
       */
      const links = [...doc.querySelectorAll(
        'a[href*="/logbook/set.php"], a[href*="/logbook/ticklists/"]'
      )].map((a) => ({
        name: clean(a.textContent),
        url: a.getAttribute('href') || '',
      }));

      const byUrl = new Map();
      for (const link of links) {
        if (!link.url) continue;

        const url = link.url.charAt(0) === '/' ? ORIGIN + link.url : link.url;
        const known = byUrl.get(url);

        // The titled anchor wins over the thumbnail's empty one.
        if (!known || (!known.length && link.name.length)) byUrl.set(url, link.name);
      }

      const lists = [];

      for (const [url, name] of byUrl) {
        if (!name) continue;
        if (lists.length >= 40) break;
        try {
          const page = await fetch(url, { credentials: 'include' });
          if (!page.ok) continue;

          const listDoc = new DOMParser()
            .parseFromString(await page.text(), 'text/html');

          const climbs = [...new Set(
            [...listDoc.querySelectorAll('a[href*="/logbook/crags/"]')]
              .map((a) => a.getAttribute('href'))
              .filter((h) => /\/logbook\/crags\/[^/]+\/[^/]+-\d+\/?$/.test(h))
              .map((h) => (h.charAt(0) === '/' ? ORIGIN + h : h))
          )];

          if (climbs.length) lists.push({ name, url, climbs });
        } catch (e) { /* skip the list, keep the rest */ }

        await sleep(250);
      }

      return lists;
    } catch (e) {
      return null;
    }
  }

  window.__ukcSyncTicks = async function (cragUrl, knownUserId) {
    try {
      let userId = parseInt(knownUserId, 10) || userIdIn(document.documentElement.outerHTML);

      // One request for the lot, when the export is reachable.
      const rows = await logbookCsv();

      if (rows) {
        Android.saveTickNames(JSON.stringify(rows));

        const wanted = await wishlist(userId);
        if (wanted) Android.saveWishlist(JSON.stringify(wanted));

        const lists = await ticklists(userId);
        if (lists) Android.saveLists(JSON.stringify(lists));

        Android.ticksDone(rows.length);
        return;
      }

      // The signed-in marker only appears on a rendered page, so borrow a crag.
      if (!userId && cragUrl) {
        const page = await navigate(cragUrl, 15000);
        if (page.html) userId = userIdIn(page.html);
      }

      if (!userId) { Android.ticksFailed('signed out'); return; }

      const urls = await logbookPages(userId);

      const wanted = await wishlist(userId);
      if (wanted) Android.saveWishlist(JSON.stringify(wanted));

      const lists = await ticklists(userId);
      if (lists) Android.saveLists(JSON.stringify(lists));

      Android.saveTicks(JSON.stringify(urls));
      Android.ticksDone(urls.length);
    } catch (e) {
      Android.ticksFailed(String(e).slice(0, 120));
    }
  };

  /** Internals, so the import can be exercised over adb without saving anything. */
  window.__ukcDebug = { parseCragData, loadCrag, navigate, resultRows };

  /** Runs a list of {name, url} crags through the worker pool. */
  async function importAll(crags, skipped, delayMs, workers) {
    if (!crags.length) { Android.finished(skipped, 0); return; }

    let next = 0, ok = 0, bad = 0, done = 0;
    let spacing = delayMs;
    let stop = false;

    // When one worker is refused, every worker waits: six of them discovering
    // the same block one after another is what turns a slowdown into a stop.
    let holdUntil = 0;

    async function waitOutAnyHold(crag) {
      while (Date.now() < holdUntil && !stop) {
        await sleep(Math.min(750, Math.max(50, holdUntil - Date.now())));

        // Keep the count alive so a long hold does not read as a dead import.
        Android.progress(done + skipped, crags.length + skipped, crag.name);
      }
    }

    async function worker() {
      // Each worker keeps its own tempo, so they do not fall into step.
      const mine = spacing * (0.8 + Math.random() * 0.4);

      while (!stop) {
        const i = next++;
        if (i >= crags.length) return;

        const crag = crags[i];
        let attempt = 0;

        await waitOutAnyHold(crag);

        while (attempt < 3 && !stop) {
          try {
            // Even with both routes guarded, nothing may settle — parsing a
            // huge page, a wedged iframe. A crag is worth a few seconds, not a
            // whole run.
            const result = await Promise.race([
              loadCrag(crag.url, LOAD_TIMEOUT_MS),
              sleep(CRAG_TIMEOUT_MS).then(() => ({ error: 'gave up waiting' })),
            ]);

            if (result.error) throw new Error(result.error);

            if (result.throttled) {
              // Back off hard and globally, then retry this crag. The hold
              // applies to every worker, and its length is scattered too — a
              // pool that all resumes on the same tick just gets refused again.
              attempt++;
              spacing = Math.min(spacing * 2, 8000);
              holdUntil = Math.max(
                holdUntil,
                Date.now() + Math.round(spacing * (2 + Math.random() * 2)),
              );
              Android.throttled(spacing);
              await waitOutAnyHold(crag);
              continue;
            }

            if (result.empty) {
              // Nothing to store, but nothing went wrong either.
              break;
            }

            if (result.crag) {
              Android.saveCrag(JSON.stringify(result.crag));
              ok++;

              // One slow patch should not hold the rest of the run at 8s.
              spacing = Math.max(delayMs, spacing * 0.85);
            } else {
              bad++;
              Android.cragFailed(
                crag.name, crag.url, 'no climbs table — ' + (result.note || '')
              );
            }
            break;
          } catch (e) {
            attempt++;
            if (attempt >= 3) {
              bad++;
              Android.cragFailed(crag.name, crag.url, String(e).slice(0, 120));
              break;
            }
            await jitter(spacing * attempt * 1.5);
          }
        }

        done++;
        Android.progress(done + skipped, crags.length + skipped, crag.name);

        await jitter(Math.max(mine, spacing));
        await breather(spacing);
      }
    }

    const pool = [];
    for (let w = 0; w < Math.max(1, workers); w++) {
      // A worker that throws anywhere outside its own retry loop would reject
      // the pool and leave the run with no ending at all — the app would sit on
      // a progress dialog for ever. One dead worker costs its share of the
      // list, not the import.
      pool.push(worker().catch((e) => {
        Android.cragFailed('worker ' + w, '', String(e).slice(0, 120));
      }));

      // Staggered, and unevenly: starting six workers together means six
      // requests landing together for the whole run.
      await jitter(spacing / Math.max(1, workers));
    }

    await Promise.all(pool);

    Android.finished(ok + skipped, bad);
  }

  /**
   * The crags a search found, named but not read. One request's worth of work:
   * the app queues these and reads them afterwards, a batch at a time.
   */
  window.__ukcResultRows = function () {
    return JSON.stringify(
      resultRows()
        .filter((c) => c.routes !== 0)
        .map((c) => ({ name: c.name, url: c.url }))
    );
  };

  window.__ukcImportResults = function (delayMs, workers, skipExisting) {
    const all = resultRows();
    if (!all.length) { Android.failed('no results on this page'); return; }

    // Empty crags have nothing to fetch; already-imported ones make re-runs cheap.
    const crags = all
      .filter((c) => c.routes !== 0)
      .filter((c) => !skipExisting || !Android.hasCrag(cragIdOf(c.url)));

    return importAll(crags, all.length - crags.length, delayMs, workers);
  };

  /** Re-reads crags already on the device, so stored data can be brought up to date. */
  window.__ukcRefreshCrags = function (json, delayMs, workers) {
    let crags = [];
    try { crags = JSON.parse(json); } catch (e) { crags = []; }

    if (!crags.length) { Android.failed('nothing to refresh'); return; }

    return importAll(crags, 0, delayMs, workers);
  };
})();
