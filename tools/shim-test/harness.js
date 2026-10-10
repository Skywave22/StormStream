/*
 * Node test harness for the StormStream JS shim.
 * Emulates the native bridges that JsPluginRuntime.kt installs:
 *   __stormHttpRequest (async), __stormKvGet/Set, __stormSelect* (sync), __stormLog, __stormUserAgent
 * then loads app/src/main/assets/storm-js-shim.js and exercises:
 *   1. a StormJS plugin (all 6 methods + kv + html)
 *   2. a Vega-style multi-module provider
 *   3. dialect detection incl. a non-plugin
 *   4. "does not implement" error path
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SHIM = fs.readFileSync(
  path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'storm-js-shim.js'),
  'utf8'
);

// ---------- canned network ----------
const NET = {
  'https://api.testflix.com/catalog/trending.json': {
    metas: [
      { id: 'tt1', title: 'Trending Movie', type: 'movie', poster: 'https://img/p1.jpg', year: 2024, rating: 8.1, description: 'A movie.', genres: ['Action'], url: 'https://testflix.com/m/tt1' },
      { id: 'tt2', title: 'Trending Series', type: 'series', poster: 'https://img/p2.jpg', year: 2023, rating: 7.5, url: 'https://testflix.com/s/tt2' },
    ],
  },
  'https://api.testflix.com/search.json?q=matrix': {
    metas: [{ id: 'tt9', title: 'The Matrix', type: 'movie', poster: 'https://img/p9.jpg', url: 'https://testflix.com/m/tt9' }],
  },
  'https://api.testflix.com/meta/tt2.json': {
    id: 'tt2', title: 'Trending Series', description: 'A great series.', genres: ['Drama', 'Sci-Fi'], rating: 7.5,
  },
  'https://api.testflix.com/page.html': '<html><body><div class="title">Scraped Title</div><a class="lnk" href="/x/1">x</a></body></html>',
  'https://scraper.example/api/12345/movie/null/null': '<html><body><span class="name">Movie Title</span></body></html>',
  'https://scraper.example/api/678/tv/2/5': '<html><body><span class="name">Show S2E5</span></body></html>',
};

const kv = {};
let httpCalls = [];

globalThis.__stormUserAgent = 'StormStreamTest/1.0';
globalThis.__stormLog = (level, msg) => { /* console.log('[js]', level, msg); */ };
globalThis.__stormKvGet = (k) => (k in kv ? kv[k] : null);
globalThis.__stormKvSet = (k, v) => { kv[k] = v; };
globalThis.__stormHttpRequest = async (method, url, body, headersJson) => {
  httpCalls.push({ method, url });
  const entry = NET[url];
  if (!entry) {
    return JSON.stringify({ status: 404, body: '', json: null, error: 'HTTP 404 for ' + url });
  }
  const isHtml = url.endsWith('.html') ||
    (typeof entry === 'string' && entry.trimStart().startsWith('<'));
  const text = isHtml ? entry : JSON.stringify(entry);
  let json = null;
  if (!isHtml) { try { json = JSON.parse(text); } catch (e) { json = null; } }
  return JSON.stringify({ status: 200, body: text, json, error: null });
};
// minimal HTML selector stubs (only what the test plugin uses)
globalThis.__stormSelectText = (html, sel) => {
  if (sel === '.title') return 'Scraped Title';
  return null;
};
globalThis.__stormSelectTextAll = (html, sel) => JSON.stringify(['Scraped Title']);
globalThis.__stormSelectAttr = (html, sel, attr) => (attr === 'href' ? '/x/1' : null);
globalThis.__stormSelectAttrAll = (html, sel, attr) => JSON.stringify(['/x/1']);
globalThis.__stormSelectHtml = (html, sel) => '<div class="title">Scraped Title</div>';
globalThis.__stormSelectHtmlAll = (html, sel) => {
  if (sel === '.lnk') return JSON.stringify(['<a class="lnk" href="/x/1">x</a>']);
  if (sel === '.name') {
    const m = String(html).match(/<span class="name">([^<]*)<\/span>/);
    return JSON.stringify([m ? '<span class="name">' + m[1] + '</span>' : '<span class="name"></span>']);
  }
  return JSON.stringify(['<div class="title">Scraped Title</div>']);
};

// ---- bridges used by the SkyStream/Nuvio dialect sections of the shim ----
const nodeCrypto = require('crypto');
globalThis.__stormFragmentText = (html) => String(html).replace(/<[^>]*>/g, '');
globalThis.__stormFragmentAttr = (html, attr) => {
  const m = String(html).match(new RegExp(attr + '="([^"]*)"'));
  return m ? m[1] : null;
};
globalThis.__stormFragmentHtml = (html) => String(html);
globalThis.__cryptoDigestHex = (hash, hex) =>
  nodeCrypto.createHash(String(hash).toLowerCase().replace(/-/g, '')).update(Buffer.from(hex, 'hex')).digest('hex');
globalThis.__cryptoHmacHex = (hash, keyHex, dataHex) =>
  nodeCrypto.createHmac(String(hash).toLowerCase().replace(/^hmac/, '').replace(/-/g, ''), Buffer.from(keyHex, 'hex'))
    .update(Buffer.from(dataHex, 'hex')).digest('hex');
globalThis.__cryptoAesHex = (mode, keyHex, ivHex, dataHex, decrypt) => {
  const bits = Buffer.from(keyHex, 'hex').length * 8;
  const algo = 'aes-' + bits + '-' + String(mode).toLowerCase();
  if (decrypt) {
    const d = nodeCrypto.createDecipheriv(algo, Buffer.from(keyHex, 'hex'), Buffer.from(ivHex, 'hex'));
    return Buffer.concat([d.update(Buffer.from(dataHex, 'hex')), d.final()]).toString('hex');
  }
  const c = nodeCrypto.createCipheriv(algo, Buffer.from(keyHex, 'hex'), Buffer.from(ivHex, 'hex'));
  return Buffer.concat([c.update(Buffer.from(dataHex, 'hex')), c.final()]).toString('hex');
};
globalThis.__stormParseUrl = (url, base) => {
  try {
    const u = new URL(String(url), base ? String(base) : undefined);
    return JSON.stringify({
      href: u.href, protocol: u.protocol, host: u.host, hostname: u.hostname,
      port: u.port, pathname: u.pathname, search: u.search, hash: u.hash, origin: u.origin,
    });
  } catch (e) {
    return JSON.stringify({ href: String(url), protocol: '', host: '', hostname: '', port: '', pathname: String(url), search: '', hash: '', origin: '' });
  }
};
const __scheduledTimers = {};
globalThis.__stormSchedule = (id, ms, code) => {
  __scheduledTimers[id] = setTimeout(() => { try { (0, eval)(code); } catch (e) {} }, ms);
};
globalThis.__stormCancelSchedule = (id) => { clearTimeout(__scheduledTimers[id]); delete __scheduledTimers[id]; };

// ---------- load shim ----------
vm.runInThisContext(SHIM, { filename: 'storm-js-shim.js' });

// ---------- module registration (same wrapping as JsPluginRuntime.kt) ----------
function registerModule(name, source) {
  const wrapped =
    'globalThis.__stormRegisterModule(' + JSON.stringify(name) + ', function (module, exports, require, storm, console, providerContext) {\n' +
    source + '\n});';
  vm.runInThisContext(wrapped, { filename: name + '.js' });
}

// ---------- plugins ----------
const STORM_PLUGIN = `
module.exports = {
  name: 'TestFlix',
  version: '1.2.3',
  description: 'Test source',
  icon: 'https://testflix.com/icon.png',
  adult: false,
  async getCatalogs() {
    return [
      { id: 'trending', name: 'Trending', type: 'movie' },
      { id: 'series', name: 'Series', type: 'series' },
    ];
  },
  async getCatalog(args) {
    const r = await storm.http.get('https://api.testflix.com/catalog/trending.json');
    storm.kv.set('lastCatalog', args.catalogId);
    return r.json.metas.map((m) => ({
      id: m.id, title: m.title, type: m.type, poster: m.poster, year: m.year,
      rating: m.rating, description: m.description, genres: m.genres, url: m.url,
    }));
  },
  async search(args) {
    const r = await storm.http.get('https://api.testflix.com/search.json?q=' + encodeURIComponent(args.query));
    return r.json.metas;
  },
  async getMeta(args) {
    const r = await storm.http.get('https://api.testflix.com/meta/tt2.json');
    return Object.assign({}, args.item, { description: r.json.description, genres: r.json.genres });
  },
  async getEpisodes(args) {
    return [
      { id: 'e1', title: 'Pilot', season: 1, number: 1, url: 'https://testflix.com/s/tt2/e1' },
      { id: 'e2', title: 'Second', season: 1, number: 2, url: 'https://testflix.com/s/tt2/e2' },
    ];
  },
  async getStreams(args) {
    const scraped = storm.html.selectText('<x/>', '.title');
    return [
      { name: 'Server A', url: 'https://cdn.testflix.com/tt2/e1.m3u8', type: 'hls', quality: '1080p',
        headers: { 'Referer': 'https://testflix.com/' },
        subtitles: [{ label: 'English', lang: 'en', url: 'https://cdn.testflix.com/e1.vtt', format: 'vtt' }] },
      { name: 'Server B', url: 'https://cdn.testflix.com/tt2/e1.mp4', type: 'mp4', quality: '720p' },
    ];
  },
};
`;

const VEGA_PLUGIN = {
  catalog: `exports.catalog = [{ title: 'Movies', filter: 'movies' }, { title: 'TV', filter: 'tv' }];`,
  posts: `
exports.getPosts = async ({ filter, page, providerContext }) => {
  return [
    { title: 'Vega Movie', link: 'https://vega.example/m/1', image: 'https://img/v1.jpg', tag: '7.9' },
    { title: 'Vega Show', link: 'https://vega.example/series/2', image: 'https://img/v2.jpg', tag: '8.4' },
  ];
};
exports.getSearchPosts = async ({ searchQuery }) => {
  return [{ title: 'Found: ' + searchQuery, link: 'https://vega.example/m/9', image: null, tag: '6.0' }];
};
`,
  meta: `
exports.getMeta = async ({ link }) => {
  return {
    title: 'Vega Movie',
    synopsis: 'A vega movie.',
    image: 'https://img/v1.jpg',
    imdbId: 'ttvega1',
    type: 'movie',
    linkList: [
      { title: 'Season 1', directLinks: [
        { title: 'Ep 1', link: 'https://vega.example/m/1/e1' },
        { title: 'Ep 2', link: 'https://vega.example/m/1/e2' },
      ]},
    ],
  };
};
`,
  stream: `
exports.getStreams = async ({ link }) => {
  return [
    { title: 'VidCloud', url: 'https://cdn.vega.example/e1.m3u8', quality: '1080p',
      headers: { Referer: 'https://vega.example/' },
      subtitles: [{ label: 'English', lang: 'en', url: 'https://cdn.vega.example/e1.srt' }] },
  ];
};
`,
};

const JUNK_PLUGIN = `module.exports = { hello: 'world' };`;

// ---------- assertions ----------
let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log('PASS  ' + name);
  } else {
    failures++;
    console.log('FAIL  ' + name + (detail ? '  -> ' + detail : ''));
  }
}

async function main() {
  // ===== StormJS plugin =====
  registerModule('main', STORM_PLUGIN);
  let info = JSON.parse(globalThis.__stormDetect());
  check('storm: dialect detected', info.dialect === 'storm', JSON.stringify(info));
  check('storm: name from exports', info.name === 'TestFlix', info.name);
  check('storm: version from exports', info.version === '1.2.3', info.version);

  let cats = JSON.parse(await globalThis.__stormInvoke('getCatalogs', '{}'));
  check('storm: getCatalogs', Array.isArray(cats) && cats.length === 2 && cats[0].id === 'trending', JSON.stringify(cats));

  let items = JSON.parse(await globalThis.__stormInvoke('getCatalog', JSON.stringify({ catalogId: 'trending', page: 1 })));
  check('storm: getCatalog maps items', items.length === 2 && items[0].title === 'Trending Movie' && items[0].rating === 8.1, JSON.stringify(items));
  check('storm: kv.set worked', kv['lastCatalog'] === 'trending', JSON.stringify(kv));

  let results = JSON.parse(await globalThis.__stormInvoke('search', JSON.stringify({ query: 'matrix', page: 1 })));
  check('storm: search', results.length === 1 && results[0].title === 'The Matrix', JSON.stringify(results));
  check('storm: search hit network', httpCalls.some((c) => c.url.includes('search.json')), JSON.stringify(httpCalls));

  let meta = JSON.parse(await globalThis.__stormInvoke('getMeta', JSON.stringify({ item: { id: 'tt2', title: 'Trending Series', type: 'series' } })));
  check('storm: getMeta enriches', meta.description === 'A great series.' && meta.genres.length === 2, JSON.stringify(meta));

  let eps = JSON.parse(await globalThis.__stormInvoke('getEpisodes', JSON.stringify({ item: { id: 'tt2' } })));
  check('storm: getEpisodes', Array.isArray(eps) && eps.length === 2 && eps[0].season === 1 && eps[0].number === 1, JSON.stringify(eps));

  let streams = JSON.parse(await globalThis.__stormInvoke('getStreams', JSON.stringify({ item: { id: 'tt2' }, episode: { id: 'e1' } })));
  check('storm: getStreams', streams.length === 2 && streams[0].url.endsWith('.m3u8') && streams[0].subtitles[0].lang === 'en', JSON.stringify(streams));
  check('storm: stream headers preserved', streams[0].headers.Referer === 'https://testflix.com/', JSON.stringify(streams[0].headers));

  // ===== Vega plugin (fresh module registry) =====
  // reset modules by re-evaluating the shim in a fresh context
  const ctx = vm.createContext(Object.assign({}, globalThis));
  // simpler: re-run shim in this same global but modules map is private… so use a subprocess-like reset:
  // Instead: run vega in a NEW vm context sharing the bridge globals.
  const vegaSandbox = {
    __stormUserAgent: globalThis.__stormUserAgent,
    __stormLog: globalThis.__stormLog,
    __stormKvGet: globalThis.__stormKvGet,
    __stormKvSet: globalThis.__stormKvSet,
    __stormHttpRequest: globalThis.__stormHttpRequest,
    __stormSelectText: globalThis.__stormSelectText,
    __stormSelectTextAll: globalThis.__stormSelectTextAll,
    __stormSelectAttr: globalThis.__stormSelectAttr,
    __stormSelectAttrAll: globalThis.__stormSelectAttrAll,
    __stormSelectHtml: globalThis.__stormSelectHtml,
    __stormSelectHtmlAll: globalThis.__stormSelectHtmlAll,
    console,
    JSON, Array, Object, String, Number, parseFloat, parseInt, RegExp, Error, Promise, encodeURIComponent,
  };
  vm.createContext(vegaSandbox);
  vm.runInContext(SHIM, vegaSandbox, { filename: 'storm-js-shim.js' });
  const reg = (name, src) => {
    const wrapped =
      'globalThis.__stormRegisterModule(' + JSON.stringify(name) + ', function (module, exports, require, storm, console, providerContext) {\n' +
      src + '\n});';
    vm.runInContext(wrapped, vegaSandbox, { filename: name + '.js' });
  };
  reg('catalog', VEGA_PLUGIN.catalog);
  reg('posts', VEGA_PLUGIN.posts);
  reg('meta', VEGA_PLUGIN.meta);
  reg('stream', VEGA_PLUGIN.stream);

  const vegaDetect = JSON.parse(vm.runInContext('globalThis.__stormDetect()', vegaSandbox));
  check('vega: dialect detected', vegaDetect.dialect === 'vega', JSON.stringify(vegaDetect));

  const vCats = JSON.parse(await vm.runInContext('globalThis.__stormInvoke("getCatalogs", "{}")', vegaSandbox));
  check('vega: catalogs from exports.catalog', vCats.length === 2 && vCats[0].id === 'movies' && vCats[0].name === 'Movies', JSON.stringify(vCats));

  const vItems = JSON.parse(await vm.runInContext('globalThis.__stormInvoke("getCatalog", JSON.stringify({ catalogId: "movies", page: 1 }))', vegaSandbox));
  check('vega: posts->items', vItems.length === 2 && vItems[0].title === 'Vega Movie' && vItems[0].rating === 7.9 && vItems[0].id === 'https://vega.example/m/1', JSON.stringify(vItems));
  check('vega: series link typed as series', vItems[1].type === 'series', vItems[1].type);

  const vSearch = JSON.parse(await vm.runInContext('globalThis.__stormInvoke("search", JSON.stringify({ query: "abc", page: 1 }))', vegaSandbox));
  check('vega: search posts', vSearch.length === 1 && vSearch[0].title === 'Found: abc', JSON.stringify(vSearch));

  const vMeta = JSON.parse(await vm.runInContext('globalThis.__stormInvoke("getMeta", JSON.stringify({ item: { id: "x", title: "Vega Movie", url: "https://vega.example/m/1" } }))', vegaSandbox));
  check('vega: meta->item (keeps original id)', vMeta.title === 'Vega Movie' && vMeta.description === 'A vega movie.' && vMeta.id === 'x' && vMeta.url === 'https://vega.example/m/1', JSON.stringify(vMeta));

  const vEps = JSON.parse(await vm.runInContext('globalThis.__stormInvoke("getEpisodes", JSON.stringify({ item: { id: "x", url: "https://vega.example/m/1" } }))', vegaSandbox));
  check('vega: meta linkList->episodes', Array.isArray(vEps) && vEps.length === 2 && vEps[0].season === 1 && vEps[0].number === 1 && vEps[0].url === 'https://vega.example/m/1/e1', JSON.stringify(vEps));

  const vStreams = JSON.parse(await vm.runInContext('globalThis.__stormInvoke("getStreams", JSON.stringify({ item: { id: "x", url: "https://vega.example/m/1" }, episode: { id: "e1", url: "https://vega.example/m/1/e1" } }))', vegaSandbox));
  check('vega: streams', vStreams.length === 1 && vStreams[0].url === 'https://cdn.vega.example/e1.m3u8' && vStreams[0].subtitles[0].url.endsWith('.srt'), JSON.stringify(vStreams));

  // ===== junk plugin -> dialect none =====
  const junkSandbox = Object.assign({}, vegaSandbox);
  vm.createContext(junkSandbox);
  vm.runInContext(SHIM, junkSandbox, { filename: 'storm-js-shim.js' });
  vm.runInContext(
    'globalThis.__stormRegisterModule("main", function (module, exports, require, storm, console, providerContext) {\n' + JUNK_PLUGIN + '\n});',
    junkSandbox
  );
  const junkDetect = JSON.parse(vm.runInContext('globalThis.__stormDetect()', junkSandbox));
  check('junk: dialect none', junkDetect.dialect === 'none', JSON.stringify(junkDetect));

  // ===== missing method error =====
  let err = null;
  try {
    await vm.runInContext('globalThis.__stormInvoke("getStreams", "{}")', junkSandbox);
  } catch (e) {
    err = e;
  }
  // junk module has no getStreams and dialect none -> storm path -> "does not implement"
  check('junk: missing method throws', !!err, 'no error thrown');
  check('junk: error mentions implement', err && /does not implement/.test(String(err.message || err)), String(err && err.message));

  // ===== storm plugin: missing optional method (search absent) =====
  const minimalSandbox = Object.assign({}, vegaSandbox);
  vm.createContext(minimalSandbox);
  vm.runInContext(SHIM, minimalSandbox, { filename: 'storm-js-shim.js' });
  vm.runInContext(
    'globalThis.__stormRegisterModule("main", function (module, exports, require, storm, console, providerContext) {\n' +
    'module.exports = { name: "Minimal", getCatalogs: async () => [{ id: "a", name: "A" }] };\n});',
    minimalSandbox
  );
  const minDetect = JSON.parse(vm.runInContext('globalThis.__stormDetect()', minimalSandbox));
  check('minimal: dialect storm', minDetect.dialect === 'storm', JSON.stringify(minDetect));
  let minErr = null;
  try {
    await vm.runInContext('globalThis.__stormInvoke("search", JSON.stringify({ query: "x" }))', minimalSandbox);
  } catch (e) { minErr = e; }
  check('minimal: search not implemented', minErr && /does not implement/.test(String(minErr.message || minErr)), String(minErr && minErr.message));

  // ===== SkyStream + Nuvio dialects (fresh sandbox per dialect) =====
  function createSandbox(beforeShim) {
    const sandbox = {
      __stormUserAgent: globalThis.__stormUserAgent,
      __stormLog: globalThis.__stormLog,
      __stormKvGet: globalThis.__stormKvGet,
      __stormKvSet: globalThis.__stormKvSet,
      __stormHttpRequest: globalThis.__stormHttpRequest,
      __stormSelectText: globalThis.__stormSelectText,
      __stormSelectTextAll: globalThis.__stormSelectTextAll,
      __stormSelectAttr: globalThis.__stormSelectAttr,
      __stormSelectAttrAll: globalThis.__stormSelectAttrAll,
      __stormSelectHtml: globalThis.__stormSelectHtml,
      __stormSelectHtmlAll: globalThis.__stormSelectHtmlAll,
      __stormFragmentText: globalThis.__stormFragmentText,
      __stormFragmentAttr: globalThis.__stormFragmentAttr,
      __stormFragmentHtml: globalThis.__stormFragmentHtml,
      __cryptoDigestHex: globalThis.__cryptoDigestHex,
      __cryptoHmacHex: globalThis.__cryptoHmacHex,
      __cryptoAesHex: globalThis.__cryptoAesHex,
      __stormParseUrl: globalThis.__stormParseUrl,
      console, JSON, Array, Object, String, Number, Boolean, Math, Date,
      parseFloat, parseInt, RegExp, Error, Promise, Uint8Array, TextEncoder, TextDecoder,
      encodeURIComponent, decodeURIComponent, setTimeout, clearTimeout,
    };
    vm.createContext(sandbox);
    if (beforeShim) beforeShim(sandbox);
    vm.runInContext(SHIM, sandbox, { filename: 'storm-js-shim.js' });
    return sandbox;
  }
  // script-mode evaluation (same wrapping as JsPluginRuntime scriptMode)
  function evalScript(sandbox, source) {
    vm.runInContext(
      'var module = { exports: {} };\nvar exports = module.exports;\n' + source,
      sandbox, { filename: 'plugin.js' }
    );
  }

  const SKY_CALLBACK_PLUGIN = `
var skyKv = {};
function getHome(cb) {
  cb({ success: true, data: {
    'Trending': [ new MultimediaItem({ title: 'Sky Movie', url: 'https://sky.example/m/1', posterUrl: 'https://img/s1.jpg', type: 'movie', year: 2024, score: 8.4 }) ],
    'Shows': [ new MultimediaItem({ title: 'Sky Show', url: 'https://sky.example/s/1', posterUrl: 'https://img/s2.jpg', type: 'series' }) ],
  }});
}
function search(query, cb) {
  cb({ success: true, data: [ { title: 'Found: ' + query, url: 'https://sky.example/m/' + query, posterUrl: 'https://img/s3.jpg', type: 'movie' } ] });
}
function load(url, cb) {
  cb({ success: true, data: {
    title: 'Sky Movie', url: url, description: 'sky desc', type: 'movie', year: 2024, score: 8.4,
    episodes: [ { season: 1, episode: 1, title: 'Pilot', url: url + '/e1' } ],
  }});
}
function loadStreams(url, cb) {
  cb({ success: true, data: [ new StreamResult({ url: url + '.m3u8', source: 'SkyCDN', quality: '1080p', headers: { Referer: 'https://sky.example/' } }) ] });
}
function helper() { return getPreference('k').then(function (v) { return v; }); }
`;

  const skySandbox = createSandbox();
  evalScript(skySandbox, SKY_CALLBACK_PLUGIN);
  const skyDetect = JSON.parse(vm.runInContext('globalThis.__stormDetect()', skySandbox));
  check('skystream: dialect detected (auto)', skyDetect.dialect === 'skystream', JSON.stringify(skyDetect));

  const skyHome = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("getHome", "[]")', skySandbox));
  check('skystream: getHome sections (callback style)', skyHome && skyHome.Trending && skyHome.Trending.length === 1 && skyHome.Trending[0].title === 'Sky Movie' && skyHome.Shows[0].type === 'series', JSON.stringify(skyHome));

  const skySearch = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("search", JSON.stringify(["matrix"]))', skySandbox));
  check('skystream: search positional (callback style)', Array.isArray(skySearch) && skySearch[0].title === 'Found: matrix', JSON.stringify(skySearch));

  const skyLoad = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("load", JSON.stringify(["https://sky.example/m/1"]))', skySandbox));
  check('skystream: load returns details', skyLoad && skyLoad.title === 'Sky Movie' && skyLoad.description === 'sky desc' && Array.isArray(skyLoad.episodes) && skyLoad.episodes.length === 1, JSON.stringify(skyLoad));

  const skyStreams = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("loadStreams", JSON.stringify(["https://sky.example/m/1/e1"]))', skySandbox));
  check('skystream: loadStreams', Array.isArray(skyStreams) && skyStreams[0].url === 'https://sky.example/m/1/e1.m3u8' && skyStreams[0].source === 'SkyCDN', JSON.stringify(skyStreams));

  // promise-style SkyStream plugin (async functions, no callback)
  const SKY_PROMISE_PLUGIN = `
async function getHome() { return { 'New': [ { title: 'Promise Movie', url: 'https://sky.example/m/9', posterUrl: 'https://img/p9.jpg', type: 'movie' } ] }; }
async function search(q) { return [ { title: 'P:' + q, url: 'https://sky.example/m/p', type: 'movie' } ]; }
`;
  const skyP = createSandbox();
  evalScript(skyP, SKY_PROMISE_PLUGIN);
  const skyPHome = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("getHome", "[]")', skyP));
  check('skystream: getHome promise style', skyPHome && skyPHome.New && skyPHome.New[0].title === 'Promise Movie', JSON.stringify(skyPHome));

  // forced dialect + manifest global + http_get + preferences + cheerio + crypto
  const skyF = createSandbox((sb) => {
    sb.__stormManifestJson = JSON.stringify({ packageName: "com.test.sky", name: "TestSky", baseUrl: "https://sky.example" });
  });
  evalScript(skyF, SKY_CALLBACK_PLUGIN);
  const skyFDetect = JSON.parse(vm.runInContext('globalThis.__stormDetect("skystream")', skyF));
  check('skystream: forced dialect', skyFDetect.dialect === 'skystream', JSON.stringify(skyFDetect));
  const skyManifest = vm.runInContext('globalThis.manifest.name + "|" + globalThis.manifest.baseUrl', skyF);
  check('skystream: manifest global injected', skyManifest === 'TestSky|https://sky.example', skyManifest);
  const skyHttp = await vm.runInContext(
    '(async () => { const r = await http_get("https://api.testflix.com/catalog/trending.json"); return r.status + ":" + (r.body.indexOf("Trending Movie") >= 0); })()',
    skyF
  );
  check('skystream: http_get bridge', skyHttp === '200:true', String(skyHttp));
  await vm.runInContext('setPreference("mykey", "myvalue")', skyF);
  const skyPref = await vm.runInContext('getPreference("mykey")', skyF);
  check('skystream: setPreference/getPreference', skyPref === 'myvalue', String(skyPref));
  const skyCheerio = await vm.runInContext(
    "(async () => { const $ = cheerio.load('<html><body><a class=lnk href=/x/1>x</a></body></html>'); return $('.lnk').attr('href') + '|' + $('.lnk').text(); })()",
    skyF
  );
  check('skystream: cheerio subset (attr/text)', skyCheerio === '/x/1|x', String(skyCheerio));
  const skyMd5 = vm.runInContext('CryptoJS.MD5("abc").toString()', skyF);
  check('skystream: CryptoJS.MD5 known answer', skyMd5 === '900150983cd24fb0d6963f7d28e17f72', skyMd5);
  const skySha = vm.runInContext('CryptoJS.SHA256("abc").toString()', skyF);
  check('skystream: CryptoJS.SHA256 known answer', skySha === 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', skySha);
  const skyAes = await vm.runInContext(
    '(async () => { const enc = CryptoJS.AES.encrypt("hello world", CryptoJS.enc.Utf8.parse("0123456789abcdef"), { iv: CryptoJS.enc.Utf8.parse("abcdef9876543210"), mode: "CBC" }); const dec = CryptoJS.AES.decrypt(enc.ciphertext, CryptoJS.enc.Utf8.parse("0123456789abcdef"), { iv: CryptoJS.enc.Utf8.parse("abcdef9876543210"), mode: "CBC" }); return dec.toString(CryptoJS.enc.Utf8); })()',
    skyF
  );
  check('skystream: CryptoJS AES CBC round-trip', skyAes === 'hello world', String(skyAes));
  const skyFetch = await vm.runInContext(
    '(async () => { const r = await fetch("https://api.testflix.com/catalog/trending.json"); const j = await r.json(); return r.ok + ":" + j.metas[0].title; })()',
    skyF
  );
  check('skystream: fetch bridge (ok+json)', skyFetch === 'true:Trending Movie', String(skyFetch));
  const skyUrl = vm.runInContext('new URL("/x/y?a=1", "https://sky.example/base/").pathname', skyF);
  check('skystream: URL polyfill', skyUrl === '/x/y', String(skyUrl));

  // ===== Nuvio scraper =====
  const NUVIO_SCRAPER = `
module.exports.getStreams = async function (tmdbId, mediaType, season, episode) {
  if (SCRAPER_ID !== 'test-scraper') throw new Error('SCRAPER_ID not injected');
  if (SCRAPER_SETTINGS.apiKey !== 'sekrit') throw new Error('SCRAPER_SETTINGS not injected');
  const r = await fetch('https://scraper.example/api/' + tmdbId + '/' + mediaType + '/' + season + '/' + episode);
  const doc = await parseHtml(await r.text());
  const title = doc.querySelector('.name').textContent;
  const $ = cheerio.load('<html><body><a class="lnk" href="/dl/1">dl</a></body></html>');
  return [
    { title: title, url: 'https://cdn.example/' + tmdbId + '.m3u8', quality: '1080p', headers: { Referer: 'https://scraper.example/' }, subtitles: [{ url: 'https://cdn.example/' + tmdbId + '.vtt', language: 'en', name: 'English' }] },
    { title: 'torrent-only', infoHash: 'abcdef' }, // must be filtered out by the host (no url)
  ];
};
module.exports.onSettings = async function () {
  return [
    { type: 'text', key: 'apiKey', title: 'API Key', defaultValue: 'sekrit' },
    { type: 'select', key: 'quality', title: 'Quality', defaultValue: '1080p', options: [ { label: 'HD', value: '1080p' }, { label: 'SD', value: '720p' } ] },
  ];
};
`;
  const nuvioSandbox = createSandbox((sb) => {
    sb.__stormScraperId = "test-scraper";
    sb.__stormScraperSettingsJson = JSON.stringify({ apiKey: "sekrit" });
  });
  evalScript(nuvioSandbox, NUVIO_SCRAPER);
  const nuvioDetect = JSON.parse(vm.runInContext('globalThis.__stormDetect("nuvio")', nuvioSandbox));
  check('nuvio: forced dialect', nuvioDetect.dialect === 'nuvio', JSON.stringify(nuvioDetect));
  const nuvioStreams = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("getStreams", JSON.stringify(["12345", "movie", null, null]))', nuvioSandbox));
  check('nuvio: getStreams positional', Array.isArray(nuvioStreams) && nuvioStreams.length === 2 && nuvioStreams[0].url === 'https://cdn.example/12345.m3u8' && nuvioStreams[0].quality === '1080p', JSON.stringify(nuvioStreams));
  const nuvioEps = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("getStreams", JSON.stringify(["678", "tv", 2, 5]))', nuvioSandbox));
  check('nuvio: getStreams episode args reach plugin', Array.isArray(nuvioEps) && nuvioEps[0].title === 'Show S2E5', JSON.stringify(nuvioEps));
  const nuvioSettings = JSON.parse(await vm.runInContext('globalThis.__stormInvokeArray("onSettings", "[]")', nuvioSandbox));
  check('nuvio: onSettings layout', Array.isArray(nuvioSettings) && nuvioSettings.length === 2 && nuvioSettings[1].options.length === 2 && nuvioSettings[1].options[0].value === '1080p', JSON.stringify(nuvioSettings));
  const nuvioAuto = JSON.parse(vm.runInContext('globalThis.__stormDetect()', nuvioSandbox));
  check('nuvio: dialect detected (auto)', nuvioAuto.dialect === 'nuvio', JSON.stringify(nuvioAuto));

  console.log(failures === 0 ? '\nALL TESTS PASSED' : '\n' + failures + ' TEST(S) FAILED');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('HARNESS ERROR', e); process.exit(2); });
