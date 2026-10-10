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

  // =====================================================================
  // Shared polyfills + SkyStream/Nuvio plugin APIs.
  // (Learning from the SkyStream and Nuvio plugin ecosystems — reimplemented
  // against StormStream's own ksoup/okhttp bridges.)
  // =====================================================================
  if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
  if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
  if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

  // ---- TextEncoder / TextDecoder (UTF-8) ----
  if (typeof globalThis.TextEncoder === 'undefined') {
    globalThis.TextEncoder = function TextEncoder() {};
    globalThis.TextEncoder.prototype.encode = function (str) {
      str = String(str === undefined ? '' : str);
      var bytes = [];
      for (var i = 0; i < str.length; i++) {
        var c = str.charCodeAt(i);
        if (c < 0x80) { bytes.push(c); }
        else if (c < 0x800) { bytes.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F)); }
        else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
          var cp = 0x10000 + ((c - 0xD800) << 10) + (str.charCodeAt(++i) - 0xDC00);
          bytes.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
        } else { bytes.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F)); }
      }
      return new Uint8Array(bytes);
    };
  }
  if (typeof globalThis.TextDecoder === 'undefined') {
    globalThis.TextDecoder = function TextDecoder(encoding) { this.encoding = String(encoding || 'utf-8').toLowerCase(); };
    globalThis.TextDecoder.prototype.decode = function (input) {
      var bytes = input instanceof Uint8Array ? input : new Uint8Array(input || []);
      var out = '', i = 0;
      while (i < bytes.length) {
        var b = bytes[i];
        if (b < 0x80) { out += String.fromCharCode(b); i += 1; }
        else if (b < 0xE0) { out += String.fromCharCode(((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F)); i += 2; }
        else if (b < 0xF0) { out += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F)); i += 3; }
        else {
          var cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
          cp -= 0x10000;
          out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
          i += 4;
        }
      }
      return out;
    };
  }

  // ---- Blob (minimal) ----
  if (typeof globalThis.Blob === 'undefined') {
    globalThis.Blob = function Blob(parts, opts) { this._parts = parts || []; this.type = (opts && opts.type) || ''; };
    globalThis.Blob.prototype.text = function () {
      var self = this;
      return Promise.resolve(self._parts.map(function (p) {
        if (typeof p === 'string') return p;
        if (p instanceof Uint8Array) return new TextDecoder().decode(p);
        return String(p);
      }).join(''));
    };
    globalThis.Blob.prototype.arrayBuffer = function () {
      var self = this;
      return self.text().then(function (t) { return new TextEncoder().encode(t).buffer; });
    };
  }

  // ---- URL (native-backed) + URLSearchParams ----
  if (typeof globalThis.URL === 'undefined' && typeof globalThis.__stormParseUrl === 'function') {
    globalThis.URL = function URL(url, base) {
      var parsed = JSON.parse(globalThis.__stormParseUrl(String(url), base ? String(base) : ''));
      this.href = parsed.href || String(url);
      this.protocol = parsed.protocol || '';
      this.host = parsed.host || '';
      this.hostname = parsed.hostname || '';
      this.port = parsed.port || '';
      this.pathname = parsed.pathname || '';
      this.search = parsed.search || '';
      this.hash = parsed.hash || '';
      this.origin = parsed.origin || '';
    };
  }
  if (typeof globalThis.URLSearchParams === 'undefined') {
    globalThis.URLSearchParams = function URLSearchParams(init) {
      this._pairs = [];
      if (typeof init === 'string') {
        var str = init.charAt(0) === '?' ? init.slice(1) : init;
        if (str) {
          var self = this;
          str.split('&').forEach(function (kv) {
            var i = kv.indexOf('=');
            if (i >= 0) self._pairs.push([decodeURIComponent(kv.slice(0, i)), decodeURIComponent(kv.slice(i + 1))]);
            else self._pairs.push([decodeURIComponent(kv), '']);
          });
        }
      }
    };
    globalThis.URLSearchParams.prototype.get = function (k) {
      for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === k) return this._pairs[i][1];
      return null;
    };
    globalThis.URLSearchParams.prototype.set = function (k, v) {
      for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === k) { this._pairs[i][1] = String(v); return; }
      this._pairs.push([String(k), String(v)]);
    };
    globalThis.URLSearchParams.prototype.append = function (k, v) { this._pairs.push([String(k), String(v)]); };
    globalThis.URLSearchParams.prototype.has = function (k) { return this.get(k) !== null; };
    globalThis.URLSearchParams.prototype.toString = function () {
      return this._pairs.map(function (p) { return encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1]); }).join('&');
    };
  }

  // ---- Timers (native-scheduled so they fire between evaluations too) ----
  globalThis.__stormTimers = {};
  var __timerSeq = 0;
  if (typeof globalThis.__stormSchedule === 'function') {
    globalThis.setTimeout = function (fn, ms) {
      var id = ++__timerSeq;
      globalThis.__stormTimers[id] = fn;
      globalThis.__stormSchedule(id, Math.max(0, Number(ms) || 0),
        'globalThis.__stormTimers[' + id + '] && globalThis.__stormTimers[' + id + ']()');
      return id;
    };
    globalThis.clearTimeout = function (id) {
      delete globalThis.__stormTimers[id];
      if (typeof globalThis.__stormCancelSchedule === 'function') globalThis.__stormCancelSchedule(id);
    };
    globalThis.setInterval = function (fn, ms) {
      var id = ++__timerSeq;
      var tick = function () {
        globalThis.__stormTimers[id] = tick;
        try { fn(); } catch (e) { globalThis.__stormLog('error', 'setInterval callback failed: ' + e); }
        globalThis.__stormSchedule(id, Math.max(1, Number(ms) || 0),
          'globalThis.__stormTimers[' + id + '] && globalThis.__stormTimers[' + id + ']()');
      };
      globalThis.__stormTimers[id] = tick;
      globalThis.__stormSchedule(id, Math.max(1, Number(ms) || 0),
        'globalThis.__stormTimers[' + id + '] && globalThis.__stormTimers[' + id + ']()');
      return id;
    };
    globalThis.clearInterval = globalThis.clearTimeout;
  }

  // ---- cheerio-compatible subset (ksoup-backed) ----
  function __fragText(html) { return globalThis.__stormFragmentText(String(html)); }
  function __fragAttr(html, attr) { return globalThis.__stormFragmentAttr(String(html), String(attr)); }
  function __fragHtml(html) { return globalThis.__stormFragmentHtml(String(html)); }
  function __selAll(html, sel) { return JSON.parse(globalThis.__stormSelectHtmlAll(String(html), String(sel))); }

  function __cheerioWrap(elements) {
    var col = {
      length: elements.length,
      text: function () { return elements.map(__fragText).join(''); },
      html: function () { return elements.length ? __fragHtml(elements[0]) : null; },
      attr: function (name) { return elements.length ? __fragAttr(elements[0], name) : undefined; },
      each: function (fn) {
        for (var i = 0; i < elements.length; i++) fn.call(elements[i], i, __cheerioWrap([elements[i]]));
        return col;
      },
      map: function (fn) {
        var out = [];
        for (var i = 0; i < elements.length; i++) out.push(fn.call(elements[i], i, __cheerioWrap([elements[i]])));
        return { get: function (i) { return i === undefined ? out : out[i]; }, toArray: function () { return out; } };
      },
      first: function () { return __cheerioWrap(elements.slice(0, 1)); },
      last: function () { return __cheerioWrap(elements.slice(-1)); },
      eq: function (i) { return __cheerioWrap(elements.slice(i, i + 1)); },
      find: function (sel) {
        var out = [];
        for (var i = 0; i < elements.length; i++) out = out.concat(__selAll(elements[i], sel));
        return __cheerioWrap(out);
      },
      filter: function (fn) {
        var out = [];
        for (var i = 0; i < elements.length; i++) {
          if (fn.call(elements[i], i, __cheerioWrap([elements[i]]))) out.push(elements[i]);
        }
        return __cheerioWrap(out);
      },
      toArray: function () { return elements.slice(); },
      get: function (i) { return i === undefined ? elements.slice() : elements[i]; }
    };
    for (var i = 0; i < elements.length; i++) col[i] = elements[i];
    return col;
  }

  globalThis.cheerio = {
    load: function (html) {
      html = String(html === undefined || html === null ? '' : html);
      var $ = function (selector) {
        if (typeof selector === 'string' && selector.charAt(0) === '<') return __cheerioWrap([selector]);
        return __cheerioWrap(__selAll(html, selector));
      };
      $.html = function () { return html; };
      $.root = function () { return __cheerioWrap([html]); };
      return $;
    }
  };

  // ---- parseHtml document facade (querySelector/All over ksoup) ----
  function __skyNode(html) {
    return {
      __html: html,
      get textContent() { return __fragText(html); },
      getAttribute: function (name) { return __fragAttr(html, name) || null; },
      querySelector: function (sel) {
        var m = __selAll(html, sel);
        return m.length ? __skyNode(m[0]) : null;
      },
      querySelectorAll: function (sel) {
        return __selAll(html, sel).map(__skyNode);
      }
    };
  }
  globalThis.parseHtml = function (html) { return Promise.resolve(__skyNode(String(html))); };
  globalThis.parseHtmlSync = function (html) { return __skyNode(String(html)); };
  globalThis.parse_html = function (html, selector, attr) {
    var out = __selAll(String(html), String(selector)).map(function (el) {
      return attr ? __fragAttr(el, attr) : __fragText(el);
    });
    return Promise.resolve(out);
  };
  globalThis.JSDOM = function JSDOM(html) { this.window = { document: __skyNode(String(html)) }; };

  // ---- fetch (Response-like) ----
  globalThis.fetch = async function (url, init) {
    init = init || {};
    var method = String(init.method || 'GET').toUpperCase();
    var headers = init.headers || {};
    var body = init.body === undefined || init.body === null
      ? ''
      : (typeof init.body === 'string' ? init.body : new TextDecoder().decode(new Uint8Array(Array.prototype.slice.call(new TextEncoder().encode(String(init.body))))));
    var r = JSON.parse(await globalThis.__stormHttpRequest(method, String(url), body, JSON.stringify(headers)));
    var bodyText = r.body || '';
    return {
      ok: r.status >= 200 && r.status < 300,
      status: r.status,
      statusText: '',
      url: String(url),
      headers: { get: function () { return null; } },
      text: async function () { return bodyText; },
      json: async function () { return JSON.parse(bodyText); },
      arrayBuffer: async function () { return new TextEncoder().encode(bodyText).buffer; }
    };
  };

  // ---- CryptoJS-compatible subset (native digest/HMAC/AES bridges) ----
  function __hexToBytes(hex) {
    var out = [];
    for (var i = 0; i + 1 < hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
    return out;
  }
  function __bytesToHex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    return out;
  }
  function __utf8Bytes(str) { return Array.prototype.slice.call(new TextEncoder().encode(String(str))); }
  function __toBytes(data) {
    if (data === null || data === undefined) return [];
    if (typeof data === 'string') return __utf8Bytes(data);
    if (data instanceof Uint8Array) return Array.prototype.slice.call(data);
    if (Array.isArray(data)) return data;
    if (data.words && typeof data.sigBytes === 'number') {
      var bytes = [];
      for (var i = 0; i < data.sigBytes; i++) bytes.push((data.words[i >> 2] >>> (24 - (i % 4) * 8)) & 0xFF);
      return bytes;
    }
    return __utf8Bytes(String(data));
  }
  function __wordArray(bytes) {
    var words = [];
    for (var i = 0; i < bytes.length; i++) words[i >> 2] = (words[i >> 2] || 0) | (bytes[i] << (24 - (i % 4) * 8));
    return {
      words: words,
      sigBytes: bytes.length,
      toString: function (encoder) {
        if (!encoder || encoder === CryptoJS.enc.Hex) return __bytesToHex(bytes);
        if (encoder === CryptoJS.enc.Base64) return CryptoJS.enc.Base64.stringify(this);
        if (encoder === CryptoJS.enc.Utf8) return new TextDecoder().decode(new Uint8Array(bytes));
        return __bytesToHex(bytes);
      }
    };
  }
  var __b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function __b64Stringify(bytes) {
    var out = '', i;
    for (i = 0; i + 2 < bytes.length; i += 3) {
      var n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
      out += __b64chars[(n >> 18) & 63] + __b64chars[(n >> 12) & 63] + __b64chars[(n >> 6) & 63] + __b64chars[n & 63];
    }
    var rem = bytes.length - i;
    if (rem === 1) { var n1 = bytes[i] << 16; out += __b64chars[(n1 >> 18) & 63] + __b64chars[(n1 >> 12) & 63] + '=='; }
    else if (rem === 2) { var n2 = (bytes[i] << 16) | (bytes[i + 1] << 8); out += __b64chars[(n2 >> 18) & 63] + __b64chars[(n2 >> 12) & 63] + __b64chars[(n2 >> 6) & 63] + '='; }
    return out;
  }
  function __b64Parse(str) {
    str = String(str).replace(/[^A-Za-z0-9+/=]/g, '');
    var out = [];
    for (var i = 0; i + 3 < str.length + 1; i += 4) {
      var c0 = __b64chars.indexOf(str.charAt(i)), c1 = __b64chars.indexOf(str.charAt(i + 1));
      if (c0 < 0 || c1 < 0) break;
      var c2 = str.charAt(i + 2) === '=' ? 0 : __b64chars.indexOf(str.charAt(i + 2));
      var c3 = str.charAt(i + 3) === '=' ? 0 : __b64chars.indexOf(str.charAt(i + 3));
      var n = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
      out.push((n >> 16) & 255);
      if (str.charAt(i + 2) !== '=') out.push((n >> 8) & 255);
      if (str.charAt(i + 3) !== '=') out.push(n & 255);
    }
    return out;
  }
  var CryptoJS = {
    lib: {
      WordArray: {
        create: function (words, sigBytes) {
          return { words: words || [], sigBytes: sigBytes || 0, toString: function (enc) { return __wordArray(__toBytes(this)).toString(enc); } };
        }
      }
    },
    enc: {
      Hex: {
        parse: function (hex) { return __wordArray(__hexToBytes(String(hex))); },
        stringify: function (wa) { return __bytesToHex(__toBytes(wa)); }
      },
      Base64: {
        parse: function (b64) { return __wordArray(__b64Parse(String(b64))); },
        stringify: function (wa) { return __b64Stringify(__toBytes(wa)); }
      },
      Utf8: {
        parse: function (str) { return __wordArray(__utf8Bytes(String(str))); },
        stringify: function (wa) { return new TextDecoder().decode(new Uint8Array(__toBytes(wa))); }
      }
    },
    MD5: function (data) { return __wordArray(__hexToBytes(globalThis.__cryptoDigestHex('MD5', __bytesToHex(__toBytes(data))))); },
    SHA1: function (data) { return __wordArray(__hexToBytes(globalThis.__cryptoDigestHex('SHA-1', __bytesToHex(__toBytes(data))))); },
    SHA256: function (data) { return __wordArray(__hexToBytes(globalThis.__cryptoDigestHex('SHA-256', __bytesToHex(__toBytes(data))))); },
    SHA512: function (data) { return __wordArray(__hexToBytes(globalThis.__cryptoDigestHex('SHA-512', __bytesToHex(__toBytes(data))))); },
    HmacMD5: function (key, data) { return __wordArray(__hexToBytes(globalThis.__cryptoHmacHex('MD5', __bytesToHex(__toBytes(key)), __bytesToHex(__toBytes(data))))); },
    HmacSHA1: function (key, data) { return __wordArray(__hexToBytes(globalThis.__cryptoHmacHex('SHA-1', __bytesToHex(__toBytes(key)), __bytesToHex(__toBytes(data))))); },
    HmacSHA256: function (key, data) { return __wordArray(__hexToBytes(globalThis.__cryptoHmacHex('SHA-256', __bytesToHex(__toBytes(key)), __bytesToHex(__toBytes(data))))); },
    HmacSHA512: function (key, data) { return __wordArray(__hexToBytes(globalThis.__cryptoHmacHex('SHA-512', __bytesToHex(__toBytes(key)), __bytesToHex(__toBytes(data))))); },
    AES: {
      decrypt: function (ciphertext, key, cfg) {
        cfg = cfg || {};
        var ct = ciphertext && ciphertext.ciphertext !== undefined ? ciphertext.ciphertext : ciphertext;
        var mode = String(cfg.mode || 'CBC').toUpperCase();
        var keyHex = __bytesToHex(__toBytes(key));
        var ivHex = cfg.iv ? __bytesToHex(__toBytes(cfg.iv)) : '';
        var dataHex = __bytesToHex(__toBytes(ct));
        return __wordArray(__hexToBytes(globalThis.__cryptoAesHex(mode, keyHex, ivHex, dataHex, true)));
      },
      encrypt: function (plaintext, key, cfg) {
        cfg = cfg || {};
        var mode = String(cfg.mode || 'CBC').toUpperCase();
        var keyHex = __bytesToHex(__toBytes(key));
        var ivHex = cfg.iv ? __bytesToHex(__toBytes(cfg.iv)) : '';
        var dataHex = __bytesToHex(__toBytes(plaintext));
        var outHex = globalThis.__cryptoAesHex(mode, keyHex, ivHex, dataHex, false);
        return { ciphertext: __wordArray(__hexToBytes(outHex)), toString: function () { return outHex; } };
      }
    }
  };
  CryptoJS.mode = { CBC: 'CBC', ECB: 'ECB' };
  CryptoJS.pad = { Pkcs7: 'Pkcs7' };
  globalThis.CryptoJS = CryptoJS;

  // ---- SkyStream plugin API surface ----
  // manifest is assigned by the host before the plugin source runs.
  globalThis.manifest = JSON.parse(globalThis.__stormManifestJson || 'null') || {};
  // SkyStream-style http helpers: http_get(url, headers?) -> Promise<{status, headers, body}>
  function __skyRequest(method, url, headers, body) {
    return globalThis.__stormHttpRequest(method, String(url), body || '', JSON.stringify(headers || {}))
      .then(function (raw) {
        var r = JSON.parse(raw);
        return { status: r.status, headers: {}, body: r.body || '' };
      });
  }
  globalThis.http_get = function (url, headers, cb) {
    var p = __skyRequest('GET', url, headers, '');
    if (typeof cb === 'function') p.then(function (r) { try { cb(r); } catch (e) {} });
    return p;
  };
  globalThis.http_post = function (url, headers, body, cb) {
    var p = __skyRequest('POST', url, headers, body);
    if (typeof cb === 'function') p.then(function (r) { try { cb(r); } catch (e) {} });
    return p;
  };
  globalThis.http_parallel = function (requests) {
    var list = Array.isArray(requests) ? requests : [];
    return Promise.all(list.map(function (r) {
      var headers = r.headers || {};
      var method = String(r.method || 'GET').toUpperCase();
      var qs = r.params
        ? '?' + Object.keys(r.params).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(r.params[k]); }).join('&')
        : '';
      return __skyRequest(method, String(r.url) + qs, headers, r.body || r.data || '');
    }));
  };
  // Preferences (SkyStream convention: async, per-plugin store)
  globalThis.getPreference = function (key) {
    try { return Promise.resolve(__kvGet(key)); } catch (e) { return Promise.resolve(null); }
  };
  globalThis.setPreference = function (key, value) {
    try { __kvSet(key, value); } catch (e) {}
    return Promise.resolve(true);
  };
  // Host classes SkyStream plugins construct
  globalThis.MultimediaItem = function MultimediaItem(params) {
    Object.assign(this, { type: 'movie', status: 'ongoing', playbackPolicy: 'none', isAdult: false, streams: [], syncData: {} }, params || {});
  };
  globalThis.Episode = function Episode(params) {
    Object.assign(this, { season: 0, episode: 0, dubStatus: 'none', playbackPolicy: 'none', streams: [] }, params || {});
  };
  globalThis.StreamResult = function StreamResult(o) {
    o = o || {};
    this.url = o.url; this.source = o.source || 'Auto'; this.quality = o.quality;
    this.headers = o.headers; this.subtitles = o.subtitles;
    this.drmKid = o.drmKid; this.drmKey = o.drmKey; this.licenseUrl = o.licenseUrl;
  };
  globalThis.Actor = function Actor(p) { if (p) Object.assign(this, p); };
  globalThis.Trailer = function Trailer(p) { if (p) Object.assign(this, p); };
  globalThis.NextAiring = function NextAiring(p) { if (p) Object.assign(this, p); };
  globalThis.CloudStream = { getLanguage: function () { return 'en'; }, getRegion: function () { return 'US'; } };
  // crypto.decryptAES(data, key, iv, options) -> Promise<string)
  // (defineProperty: some hosts expose a getter-only `crypto` global)
  function __defineGlobal(name, value) {
    try {
      Object.defineProperty(globalThis, name, { value: value, configurable: true, writable: true });
    } catch (e) {
      try { globalThis[name] = value; } catch (e2) {}
    }
  }
  __defineGlobal('crypto', {
    decryptAES: function (data, key, iv, options) {
      try {
        var mode = String((options && options.mode) || 'cbc').toUpperCase();
        var keyStr = String(key || '');
        var keyBytes = /^[0-9a-fA-F]{32,64}$/.test(keyStr) && keyStr.length % 2 === 0
          ? __hexToBytes(keyStr)
          : __utf8Bytes(keyStr);
        var ivBytes = iv ? __toBytes(iv) : [];
        var dataBytes = __toBytes(String(data || ''));
        var plainHex = globalThis.__cryptoAesHex(mode, __bytesToHex(keyBytes), __bytesToHex(ivBytes), __bytesToHex(dataBytes), true);
        return Promise.resolve(new TextDecoder().decode(new Uint8Array(__hexToBytes(plainHex))));
      } catch (e) { return Promise.reject(e); }
    }
  });
  // Dean Edwards' P.A.C.K.E.R. de-obfuscation
  globalThis.getAndUnpack = function (js) {
    try {
      var src = String(js || '');
      var m = /}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)/.exec(src);
      if (!m) return src;
      var payload = m[1], radix = parseInt(m[2], 10), count = parseInt(m[3], 10), dictSrc = m[4];
      var dict = dictSrc.split('|');
      var unescape = function (s) { return s.replace(/\\(\.)/g, '$1'); };
      payload = unescape(payload);
      dict = dict.map(unescape);
      return payload.replace(/\b\w+\b/g, function (w) {
        var idx = parseInt(w, radix);
        return !isNaN(idx) && idx >= 0 && idx < count && dict[idx] !== undefined && dict[idx] !== '' ? dict[idx] : w;
      });
    } catch (e) { return String(js || ''); }
  };

  // ---- Nuvio plugin API surface ----
  // SCRAPER_ID / SCRAPER_SETTINGS are assigned by the host before the source runs.
  globalThis.SCRAPER_ID = globalThis.__stormScraperId || '';
  globalThis.SCRAPER_SETTINGS = JSON.parse(globalThis.__stormScraperSettingsJson || '{}') || {};

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

  // Resolve a plugin function across the shapes plugins come in:
  // CommonJS module.exports, a registered module, or a top-level script global.
  function __lookupFn(fn) {
    var main = __mainModule();
    if (main && typeof main[fn] === 'function') return { owner: main, fn: main[fn] };
    if (typeof globalThis[fn] === 'function') return { owner: globalThis, fn: globalThis[fn] };
    var me = globalThis.module && globalThis.module.exports;
    if (me && typeof me[fn] === 'function') return { owner: me, fn: me[fn] };
    return null;
  }

  // SkyStream plugins come in two flavors: callback style
  //   getHome(cb) / search(query, cb) / load(url, cb) / loadStreams(url, cb)
  // with cb({success, data}), and promise style (async top-level functions
  // returning the value directly). Call with BOTH a trailing callback and an
  // awaited return — whichever settles first wins.
  function __callSky(fn, positionalArgs) {
    return new Promise(function (resolve, reject) {
      var done = false;
      function finish(err, value) {
        if (done) return;
        done = true;
        if (err) reject(err); else resolve(value === undefined ? null : value);
      }
      function cb(res) {
        try {
          if (!res) return finish(null, null);
          if (res.success === false) return finish(new Error(res.error || res.message || 'plugin call failed'));
          finish(null, res.data !== undefined ? res.data : res);
        } catch (e) { finish(e); }
      }
      try {
        var ret = fn.apply(null, positionalArgs.concat([cb]));
        if (ret && typeof ret.then === 'function') {
          ret.then(function (v) { finish(null, v); }, function (e) { finish(e); });
        }
      } catch (e) { finish(e); }
    });
  }

  globalThis.__stormDetect = function (forced) {
    var main = __mainModule();
    var dialect = 'none';
    if (forced === 'skystream' || forced === 'nuvio' || forced === 'storm' || forced === 'vega') {
      dialect = forced;
    } else {
      var stormFns = ['getCatalogs', 'getCatalog', 'search', 'getMeta', 'getEpisodes', 'getStreams'];
      var isStorm = stormFns.some(function (f) { return typeof main[f] === 'function'; });
      var me = globalThis.module && globalThis.module.exports;
      function __hasFn(name) {
        return typeof main[name] === 'function' ||
          typeof globalThis[name] === 'function' ||
          !!(me && typeof me[name] === 'function');
      }
      var isSky = __hasFn('getHome') || __hasFn('loadStreams') || __hasFn('getDetails');
      var isNuvio = !isSky && __hasFn('getStreams');
      var isVegaSingle = typeof main.getPosts === 'function' ||
        typeof main.getSearchPosts === 'function' ||
        (typeof main.getMeta === 'function' && typeof main.getStreams === 'function');
      var hasVegaModules = !!(__modules['posts'] || __modules['stream'] || __modules['meta'] || __modules['catalog']);
      if (isStorm) {
        dialect = 'storm';
      } else if (isSky) {
        dialect = 'skystream';
      } else if (isNuvio) {
        dialect = 'nuvio';
      } else if (isVegaSingle || hasVegaModules) {
        dialect = 'vega';
      }
    }
    __dialect = dialect;
    var manifest = main.manifest || globalThis.manifest || {};
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

  // Positional invoke — used by the SkyStream and Nuvio dialects, whose plugin
  // functions take positional arguments (search(query), load(url),
  // getStreams(tmdbId, mediaType, season, episode), …).
  globalThis.__stormInvokeArray = async function (fn, argsArrayJson) {
    var args = JSON.parse(argsArrayJson);
    var found = __lookupFn(fn);
    if (!found) {
      throw new Error("Plugin does not implement '" + fn + "'");
    }
    var result;
    if (__dialect === 'skystream') {
      result = await __callSky(found.fn, args);
    } else {
      result = await found.fn.apply(found.owner, args);
    }
    return JSON.stringify(result === undefined ? null : result);
  };
})();
