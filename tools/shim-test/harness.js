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
  const isHtml = url.endsWith('.html');
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
globalThis.__stormSelectHtmlAll = (html, sel) => JSON.stringify(['<div class="title">Scraped Title</div>']);

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

  console.log(failures === 0 ? '\nALL TESTS PASSED' : '\n' + failures + ' TEST(S) FAILED');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('HARNESS ERROR', e); process.exit(2); });
