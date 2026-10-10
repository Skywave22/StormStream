/*
 * StormStream JavaScript runtime shim.
 *
 * Injected into QuickJS before any plugin code. It provides:
 *   - the `storm` API available to StormJS plugins
 *       storm.http.get(url, headers?) / storm.http.post(url, body, headers?)  (async)
 *       storm.kv.get(key) / storm.kv.set(key, value)                          (sync)
 *       storm.html.selectText(html, selector)                                 (sync)
 *       storm.html.selectTextAll(html, selector)                              (sync)
 *       storm.html.selectAttr(html, selector, attr)                           (sync)
 *       storm.html.selectAttrAll(html, selector, attr)                        (sync)
 *       storm.html.selectHtml(html, selector)                                 (sync)
 *       storm.html.selectHtmlAll(html, selector)                              (sync)
 *       storm.log / storm.warn / storm.error                                 (sync)
 *   - a CommonJS module loader (__stormRegisterModule)
 *   - a Vega-provider compatibility bridge (providerContext.axios etc.)
 *   - dialect detection (__stormDetect) and dispatch (__stormInvoke)
 *
 * StormJS plugin contract (module.exports):
 *   module.exports = {
 *     name: "My Source",            // optional metadata
 *     version: "1.0.0",
 *     description: "...",           // optional
 *     icon: "https://.../icon.png", // optional
 *     adult: false,                 // optional
 *     async getCatalogs()                  -> [{ id, name, type }]
 *     async getCatalog({ catalogId, page })       -> [item]
 *     async search({ query, page })               -> [item]
 *     async getMeta({ item })                     -> item (enriched)
 *     async getEpisodes({ item })                 -> [episode] | null
 *     async getStreams({ item, episode? })        -> [stream]
 *   }
 *   item    = { id, title, type, poster, backdrop, year, rating, description, genres[], url }
 *   episode = { id, title, season, number, thumbnail, description, url }
 *   stream  = { name, url, type, quality, headers{}, subtitles[{ label, lang, url, format }] }
 */
(function () {
  'use strict';

  var __modules = {};
  var __dialect = null;

  if (typeof globalThis.AbortController === 'undefined') {
    globalThis.AbortController = function () {
      this.signal = {
        aborted: false,
        reason: undefined,
        onabort: null,
        addEventListener: function () {},
        removeEventListener: function () {},
        throwIfAborted: function () {}
      };
    };
  }
  if (typeof globalThis.AbortSignal === 'undefined') {
    globalThis.AbortSignal = function () {
      return {
        aborted: false,
        addEventListener: function () {},
        removeEventListener: function () {},
        throwIfAborted: function () {}
      };
    };
  }

  function __kvGet(key) {
    var v = globalThis.__stormKvGet(String(key));
    return v === null || v === undefined ? null : String(v);
  }
  function __kvSet(key, value) {
    globalThis.__stormKvSet(String(key), value === null || value === undefined ? '' : String(value));
  }

  // ---------- public `storm` API ----------
  var storm = {
    http: {
      get: async function (url, headers) {
        var r = JSON.parse(await globalThis.__stormHttpRequest('GET', String(url), '', JSON.stringify(headers || {})));
        if (r.error) { throw new Error('HTTP request failed: ' + r.error); }
        return { status: r.status, body: r.body, json: r.json === null ? undefined : r.json };
      },
      post: async function (url, body, headers) {
        var r = JSON.parse(await globalThis.__stormHttpRequest('POST', String(url), body === null || body === undefined ? '' : String(body), JSON.stringify(headers || {})));
        if (r.error) { throw new Error('HTTP request failed: ' + r.error); }
        return { status: r.status, body: r.body, json: r.json === null ? undefined : r.json };
      }
    },
    kv: {
      get: function (key) { return __kvGet(key); },
      set: function (key, value) { __kvSet(key, value); }
    },
    html: {
      selectText: function (html, selector) { return globalThis.__stormSelectText(String(html), String(selector)); },
      selectTextAll: function (html, selector) { return JSON.parse(globalThis.__stormSelectTextAll(String(html), String(selector))); },
      selectAttr: function (html, selector, attr) { return globalThis.__stormSelectAttr(String(html), String(selector), String(attr)); },
      selectAttrAll: function (html, selector, attr) { return JSON.parse(globalThis.__stormSelectAttrAll(String(html), String(selector), String(attr))); },
      selectHtml: function (html, selector) { return globalThis.__stormSelectHtml(String(html), String(selector)); },
      selectHtmlAll: function (html, selector) { return JSON.parse(globalThis.__stormSelectHtmlAll(String(html), String(selector))); }
    },
    log: function () { globalThis.__stormLog('info', Array.prototype.join.call(arguments, ' ')); },
    warn: function () { globalThis.__stormLog('warn', Array.prototype.join.call(arguments, ' ')); },
    error: function () { globalThis.__stormLog('error', Array.prototype.join.call(arguments, ' ')); }
  };
  globalThis.storm = storm;

  // ---------- CommonJS module loading ----------
  globalThis.__stormRegisterModule = function (name, factory) {
    var module = { exports: {} };
    var exports = module.exports;
    var require = function (dep) {
      throw new Error("require('" + dep + "') is not available in StormStream plugins. " +
        "Use the 'storm' API instead (storm.http, storm.html, storm.kv).");
    };
    factory(module, exports, require, storm, console, __vegaProviderContext());
    __modules[name] = module.exports;
  };

  // ---------- Vega compatibility bridge ----------
  function __vegaProviderContext() {
    return {
      axios: {
        get: async function (url, cfg) {
          var headers = (cfg && cfg.headers) || {};
          var r = JSON.parse(await globalThis.__stormHttpRequest('GET', String(url), '', JSON.stringify(headers)));
          if (r.error) {
            var e = new Error('Request failed: ' + r.error + ' (HTTP ' + r.status + ')');
            e.response = { status: r.status, statusText: '', config: { url: String(url) } };
            throw e;
          }
          return { status: r.status, data: r.json === null ? r.body : r.json, headers: {} };
        },
        post: async function (url, body, cfg) {
          var headers = (cfg && cfg.headers) || {};
          var r = JSON.parse(await globalThis.__stormHttpRequest('POST', String(url), body === null || body === undefined ? '' : String(body), JSON.stringify(headers)));
          if (r.error) {
            var e = new Error('Request failed: ' + r.error + ' (HTTP ' + r.status + ')');
            e.response = { status: r.status, statusText: '', config: { url: String(url) } };
            throw e;
          }
          return { status: r.status, data: r.json === null ? r.body : r.json, headers: {} };
        }
      },
      commonHeaders: {
        'User-Agent': globalThis.__stormUserAgent || 'Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36',
        'Accept': '*/*'
      },
      kvStore: {
        get: async function (key) { return __kvGet(key); },
        set: async function (key, value) { __kvSet(key, value); }
      },
      console: console
    };
  }
  globalThis.__vegaProviderContext = __vegaProviderContext;

  function __vegaCall(fn, args) {
    var full = {};
    for (var k in args) { full[k] = args[k]; }
    full.providerContext = __vegaProviderContext();
    if (!full.signal) {
      try { full.signal = new AbortController().signal; } catch (e) { full.signal = { aborted: false }; }
    }
    return fn(full);
  }

  function __vegaTypeFromLink(link) {
    if (typeof link === 'string' && /series|tv\b/i.test(link)) { return 'series'; }
    return 'movie';
  }

  function __vegaParseRating(tag) {
    if (typeof tag !== 'string') { return null; }
    var m = tag.match(/(\d+(?:\.\d+)?)/);
    return m ? parseFloat(m[1]) : null;
  }

  function __vegaPostToItem(p) {
    if (!p) { return null; }
    var link = p.link || p.url || null;
    return {
      id: String(link || p.title || ''),
      title: p.title || p.name || '',
      type: __vegaTypeFromLink(link),
      poster: p.image || p.poster || null,
      backdrop: p.background || p.backdrop || null,
      year: p.year || null,
      rating: __vegaParseRating(p.tag) || (typeof p.rating === 'number' ? p.rating : null),
      description: p.description || p.synopsis || null,
      genres: p.genres || [],
      url: link
    };
  }

  function __vegaSeasonNumber(title, index, total) {
    if (typeof title === 'string') {
      var m = title.match(/season\s*(\d+)/i);
      if (m) { return parseInt(m[1], 10); }
    }
    return total <= 1 ? 1 : index + 1;
  }

  function __vegaMetaToItem(meta, orig) {
    meta = meta || {};
    orig = orig || {};
    return {
      id: orig.id || String(meta.imdbId || meta.id || ''),
      title: meta.title || meta.name || orig.title || '',
      type: meta.type === 'series' ? 'series' : (orig.type || __vegaTypeFromLink(orig.url)),
      poster: meta.poster || meta.image || orig.poster || null,
      backdrop: meta.image || meta.background || meta.backdrop || orig.backdrop || null,
      year: meta.year || orig.year || null,
      rating: (typeof meta.rating === 'number' ? meta.rating : null) || orig.rating || null,
      description: meta.synopsis || meta.description || orig.description || null,
      genres: meta.genres || orig.genres || [],
      url: orig.url || null
    };
  }

  function __vegaMetaToEpisodes(meta) {
    meta = meta || {};
    var linkList = Array.isArray(meta.linkList) ? meta.linkList : [];
    var eps = [];
    linkList.forEach(function (season, si) {
      var seasonNum = __vegaSeasonNumber(season && season.title, si, linkList.length);
      var links = (season && (season.directLinks || season.links)) || [];
      links.forEach(function (dl, di) {
        var link = dl.link || dl.url || null;
        eps.push({
          id: String(link || ((meta.imdbId || meta.id || 'ep') + ':' + seasonNum + ':' + (di + 1))),
          title: dl.title || ('Episode ' + (di + 1)),
          season: seasonNum,
          number: dl.number || (di + 1),
          thumbnail: dl.image || dl.thumbnail || null,
          description: dl.description || null,
          url: link
        });
      });
    });
    return eps.length ? eps : null;
  }

  function __vegaStreamToStream(s) {
    if (!s) { return null; }
    var url = s.url || s.link || null;
    if (!url && Array.isArray(s.sources) && s.sources.length) {
      url = s.sources[0].url || s.sources[0].link || null;
    }
    if (!url && Array.isArray(s.streams) && s.streams.length) {
      url = s.streams[0].url || s.streams[0].link || null;
    }
    var subs = Array.isArray(s.subtitles) ? s.subtitles : [];
    return {
      name: s.title || s.name || s.serverName || s.quality || 'Stream',
      url: url,
      type: s.type || 'auto',
      quality: s.quality || s.resolution || null,
      headers: s.headers || {},
      subtitles: subs.map(function (sub) {
        return {
          label: sub.label || sub.lang || sub.language || 'Subtitle',
          lang: sub.lang || sub.language || 'und',
          url: sub.url || sub.link || '',
          format: sub.format || 'vtt'
        };
      })
    };
  }

  async function __vegaInvoke(fn, args) {
    var main = __modules['main'] || {};
    var posts = __modules['posts'] || {};
    var meta = __modules['meta'] || {};
    var stream = __modules['stream'] || {};
    var catalog = __modules['catalog'] || {};
    var episodes = __modules['episodes'] || {};
    var getPosts = posts.getPosts || main.getPosts;
    var getSearchPosts = posts.getSearchPosts || main.getSearchPosts;
    var getMeta = meta.getMeta || main.getMeta;
    var getStreams = stream.getStreams || main.getStreams;
    var catalogArr = catalog.catalog || main.catalog;

    if (fn === 'getCatalogs') {
      var cats = Array.isArray(catalogArr) ? catalogArr : [];
      return cats.map(function (c, i) {
        return {
          id: String(c.filter || c.id || ('catalog' + i)),
          name: c.title || c.name || ('Catalog ' + (i + 1)),
          type: c.type || 'movie'
        };
      });
    }
    if (fn === 'getCatalog') {
      if (typeof getPosts !== 'function') { throw new Error("Vega provider has no getPosts (posts module)"); }
      var posts = await __vegaCall(getPosts, { filter: args.catalogId, page: args.page });
      return (posts || []).map(__vegaPostToItem).filter(function (x) { return x !== null; });
    }
    if (fn === 'search') {
      if (typeof getSearchPosts !== 'function') { throw new Error("Vega provider has no getSearchPosts (posts module)"); }
      var res = await __vegaCall(getSearchPosts, { searchQuery: args.query, page: args.page });
      return (res || []).map(__vegaPostToItem).filter(function (x) { return x !== null; });
    }
    if (fn === 'getMeta') {
      if (typeof getMeta !== 'function') { throw new Error("Vega provider has no getMeta (meta module)"); }
      var m = await __vegaCall(getMeta, { link: (args.item && (args.item.url || args.item.id)) || '' });
      return __vegaMetaToItem(m, args.item);
    }
    if (fn === 'getEpisodes') {
      if (typeof getMeta !== 'function') { throw new Error("Vega provider has no getMeta (meta module)"); }
      var m2 = await __vegaCall(getMeta, { link: (args.item && (args.item.url || args.item.id)) || '' });
      return __vegaMetaToEpisodes(m2);
    }
    if (fn === 'getStreams') {
      if (typeof getStreams !== 'function') { throw new Error("Vega provider has no getStreams (stream module)"); }
      var link = (args.episode && (args.episode.url || args.episode.id)) || (args.item && (args.item.url || args.item.id)) || '';
      var streams = await __vegaCall(getStreams, { link: link, item: args.item, episode: args.episode });
      return (streams || []).map(__vegaStreamToStream).filter(function (x) { return x !== null && x.url; });
    }
    throw new Error("Unknown method '" + fn + "'");
  }

  // ---------- detection & dispatch ----------
  function __mainModule() {
    if (__modules['main']) { return __modules['main']; }
    var keys = Object.keys(__modules);
    return keys.length ? __modules[keys[0]] : {};
  }

  globalThis.__stormDetect = function () {
    var main = __mainModule();
    var stormFns = ['getCatalogs', 'getCatalog', 'search', 'getMeta', 'getEpisodes', 'getStreams'];
    var isStorm = stormFns.some(function (f) { return typeof main[f] === 'function'; });
    var isVegaSingle = typeof main.getPosts === 'function' ||
      typeof main.getSearchPosts === 'function' ||
      (typeof main.getMeta === 'function' && typeof main.getStreams === 'function');
    var hasVegaModules = !!(__modules['posts'] || __modules['stream'] || __modules['meta'] || __modules['catalog']);
    var dialect = 'none';
    if (isStorm) {
      dialect = 'storm';
    } else if (isVegaSingle || hasVegaModules) {
      dialect = 'vega';
    }
    __dialect = dialect;
    var manifest = main.manifest || {};
    return JSON.stringify({
      dialect: dialect,
      name: main.name || manifest.name || null,
      version: main.version || manifest.version || null,
      description: main.description || manifest.description || null,
      icon: main.icon || manifest.icon || null,
      adult: !!(main.adult || manifest.adult)
    });
  };

  globalThis.__stormInvoke = async function (fn, argsJson) {
    var args = JSON.parse(argsJson);
    var result;
    if (__dialect === 'vega') {
      result = await __vegaInvoke(fn, args);
    } else {
      var main = __mainModule();
      if (!main || typeof main[fn] !== 'function') {
        throw new Error("Plugin does not implement '" + fn + "'");
      }
      result = await main[fn](args);
    }
    return JSON.stringify(result === undefined ? null : result);
  };
})();
