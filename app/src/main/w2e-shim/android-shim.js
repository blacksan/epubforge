/*
 * EpubForge Android shim for WebToEpub.
 * Loaded before any WebToEpub script. Provides the chrome.* extension APIs WebToEpub
 * expects, and routes all http(s) fetches through the app's native network layer
 * (which shares cookies with the in-app browser, throttles per host, honours
 * Retry-After and pauses for user-solved verification pages).
 */
(function () {
    "use strict";
    const bridge = window.W2EBridge;
    const ASSET_ORIGIN = location.origin;

    function event(obj) {
        try { bridge && bridge.event(JSON.stringify(obj)); } catch (e) { /* ignore */ }
    }
    window.__w2eEvent = event;

    function syncGet(path) {
        try {
            const xhr = new XMLHttpRequest();
            xhr.open("GET", path, false);
            xhr.send(null);
            return (xhr.status === 200 || xhr.status === 0) ? xhr.responseText : null;
        } catch (e) {
            return null;
        }
    }

    // ---------- i18n ----------
    let messages = {};
    try { messages = JSON.parse(syncGet("locales/en/messages.json") || syncGet("_locales/en/messages.json") || "{}"); } catch (e) { messages = {}; }
    let manifest = {};
    try { manifest = JSON.parse(syncGet("manifest.json") || "{}"); } catch (e) { manifest = {}; }

    function getMessage(name, subs) {
        let entry = messages[name];
        if (!entry && name) {
            entry = messages["__MSG_" + name + "__"] || messages[name.replace(/^__MSG_/, "").replace(/__$/, "")];
        }
        if (!entry) { return ""; }
        let text = entry.message || "";
        if (subs !== undefined && subs !== null && !Array.isArray(subs)) { subs = [subs]; }
        subs = subs || [];
        if (entry.placeholders) {
            for (const [ph, def] of Object.entries(entry.placeholders)) {
                let content = String(def.content || "");
                content = content.replace(/\$(\d)/g, (m, d) => (subs[parseInt(d, 10) - 1] ?? ""));
                text = text.replace(new RegExp("\\$" + ph + "\\$", "gi"), content);
            }
        }
        text = text.replace(/\$(\d)/g, (m, d) => (subs[parseInt(d, 10) - 1] ?? ""));
        return text.replace(/\$\$/g, "$");
    }

    // ---------- chrome.storage.local (backed by localStorage) ----------
    const STORE_PREFIX = "__w2e_store__";
    function storeKeys() {
        const keys = [];
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k && k.startsWith(STORE_PREFIX)) { keys.push(k.substring(STORE_PREFIX.length)); }
        }
        return keys;
    }
    function storeGetSync(keys) {
        let wanted;
        let defaults = {};
        if (keys === null || keys === undefined) { wanted = storeKeys(); }
        else if (typeof keys === "string") { wanted = [keys]; }
        else if (Array.isArray(keys)) { wanted = keys; }
        else { defaults = keys; wanted = Object.keys(keys); }
        const out = {};
        for (const k of wanted) {
            const raw = localStorage.getItem(STORE_PREFIX + k);
            if (raw !== null) {
                try { out[k] = JSON.parse(raw); } catch (e) { out[k] = raw; }
            } else if (k in defaults) {
                out[k] = defaults[k];
            }
        }
        return out;
    }
    function cbOrPromise(value, cb) {
        if (typeof cb === "function") { setTimeout(() => cb(value), 0); return undefined; }
        return Promise.resolve(value);
    }
    const storageLocal = {
        get(keys, cb) { return cbOrPromise(storeGetSync(keys), cb); },
        set(items, cb) {
            for (const [k, v] of Object.entries(items || {})) {
                try { localStorage.setItem(STORE_PREFIX + k, JSON.stringify(v)); } catch (e) { event({ type: "log", message: "storage full: " + e }); }
            }
            return cbOrPromise(undefined, cb);
        },
        remove(keys, cb) {
            for (const k of (Array.isArray(keys) ? keys : [keys])) { localStorage.removeItem(STORE_PREFIX + k); }
            return cbOrPromise(undefined, cb);
        },
        clear(cb) {
            for (const k of storeKeys()) { localStorage.removeItem(STORE_PREFIX + k); }
            return cbOrPromise(undefined, cb);
        },
        getBytesInUse(keys, cb) {
            let n = 0;
            for (const k of storeKeys()) { n += (localStorage.getItem(STORE_PREFIX + k) || "").length; }
            return cbOrPromise(n, cb);
        }
    };

    // ---------- chrome.downloads -> hand the file to the app ----------
    const downloadListeners = [];
    let nextDownloadId = 1;
    async function deliverDownload(options) {
        const res = await window.__w2eOriginalFetch(options.url);
        const buf = new Uint8Array(await res.arrayBuffer());
        const name = options.filename || "book.epub";
        const kind = name.toLowerCase().endsWith(".epub") ? "epub" : "other";
        bridge.fileBegin(name, kind, buf.length);
        const CHUNK = 512 * 1024;
        for (let i = 0; i < buf.length; i += CHUNK) {
            const slice = buf.subarray(i, Math.min(i + CHUNK, buf.length));
            let bin = "";
            for (let j = 0; j < slice.length; j += 0x8000) {
                bin += String.fromCharCode.apply(null, slice.subarray(j, j + 0x8000));
            }
            bridge.fileChunk(btoa(bin));
        }
        bridge.fileEnd();
    }
    const downloads = {
        download(options, cb) {
            const id = nextDownloadId++;
            const p = deliverDownload(options).then(() => {
                setTimeout(() => {
                    for (const l of downloadListeners) {
                        try { l({ id: id, state: { current: "complete" } }); } catch (e) { /* ignore */ }
                    }
                }, 50);
                return id;
            }).catch(err => {
                event({ type: "error", message: "Could not save file: " + err });
                throw err;
            });
            if (typeof cb === "function") { p.then(cb, () => cb(undefined)); return undefined; }
            return p;
        },
        onChanged: { addListener(fn) { downloadListeners.push(fn); }, removeListener() {} }
    };

    const noopEvent = { addListener() {}, removeListener() {}, hasListener() { return true; } };

    window.chrome = window.chrome || {};
    Object.assign(window.chrome, {
        i18n: { getMessage: getMessage, getUILanguage: () => navigator.language || "en" },
        runtime: {
            id: "epubforge",
            lastError: null,
            getURL: (p) => ASSET_ORIGIN + "/assets/w2e/" + (p || ""),
            getManifest: () => manifest,
            getPlatformInfo: () => Promise.resolve({ os: "android", arch: "arm" }),
            onMessage: noopEvent,
            sendMessage: () => Promise.resolve()
        },
        storage: { local: storageLocal },
        downloads: downloads,
        cookies: {
            getAll: (q, cb) => cbOrPromise([], cb),
            set: (d, cb) => cbOrPromise(null, cb),
            remove: (d, cb) => cbOrPromise(null, cb)
        },
        declarativeNetRequest: {
            getSessionRules: () => Promise.resolve([]),
            updateSessionRules: () => Promise.resolve()
        },
        tabs: {
            create: (opts) => { event({ type: "openUrl", url: opts && opts.url }); return Promise.resolve({ id: 2 }); },
            query: (q, cb) => cbOrPromise([], cb),
            executeScript: () => Promise.resolve([])
        },
        scripting: { executeScript: () => Promise.resolve([]) },
        permissions: { request: () => Promise.resolve(true), contains: () => Promise.resolve(true) }
    });

    window.alert = (msg) => event({ type: "log", message: String(msg) });
    window.confirm = () => true;
    window.prompt = () => null;

    // ---------- timers ----------
    // The engine's WebView is off-screen, where Android throttles or pauses JS timers.
    // WebToEpub sleeps between chapters with setTimeout, so longer timers are scheduled
    // by the app instead and fired with evaluateJavascript, which always runs.
    const origSetTimeout = window.setTimeout.bind(window);
    const origClearTimeout = window.clearTimeout.bind(window);
    const nativeTimers = new Map();
    let nextTimerId = 1000000;
    window.__w2eFireTimer = function (id) {
        const t = nativeTimers.get(id);
        if (!t) { return; }
        nativeTimers.delete(id);
        try { t.fn.apply(window, t.args); } catch (e) { origSetTimeout(() => { throw e; }, 0); }
    };
    if (bridge && typeof bridge.timer === "function") {
        window.setTimeout = function (fn, ms, ...args) {
            ms = Number(ms) || 0;
            if (typeof fn !== "function" || ms < 100) { return origSetTimeout(fn, ms, ...args); }
            const id = nextTimerId++;
            nativeTimers.set(id, { fn: fn, args: args });
            bridge.timer(id, Math.round(ms));
            return id;
        };
        window.clearTimeout = function (id) {
            if (nativeTimers.has(id)) { nativeTimers.delete(id); return; }
            origClearTimeout(id);
        };
    }

    window.addEventListener("unhandledrejection", (e) => {
        event({ type: "log", message: "Unhandled: " + (e.reason && (e.reason.stack || e.reason.message) || e.reason) });
    });
    window.addEventListener("error", (e) => {
        event({ type: "log", message: "Script error: " + e.message + " (" + e.filename + ":" + e.lineno + ")" });
    });

    // ---------- native networking ----------
    const originalFetch = window.fetch.bind(window);
    window.__w2eOriginalFetch = originalFetch;

    function headersToObject(h) {
        const out = {};
        if (!h) { return out; }
        if (typeof Headers !== "undefined" && h instanceof Headers) {
            h.forEach((v, k) => { out[k] = v; });
        } else if (Array.isArray(h)) {
            for (const [k, v] of h) { out[k] = v; }
        } else {
            Object.assign(out, h);
        }
        return out;
    }

    async function bodyToPayload(body, headers) {
        if (body === undefined || body === null) { return null; }
        if (typeof body === "string") { return { text: body }; }
        if (body instanceof URLSearchParams) {
            if (!Object.keys(headers).some(k => k.toLowerCase() === "content-type")) {
                headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8";
            }
            return { text: body.toString() };
        }
        if (typeof FormData !== "undefined" && body instanceof FormData) {
            const p = new URLSearchParams();
            for (const [k, v] of body.entries()) { p.append(k, typeof v === "string" ? v : ""); }
            headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8";
            return { text: p.toString() };
        }
        let bytes;
        if (body instanceof Blob) { bytes = new Uint8Array(await body.arrayBuffer()); }
        else if (body instanceof ArrayBuffer) { bytes = new Uint8Array(body); }
        else if (ArrayBuffer.isView(body)) { bytes = new Uint8Array(body.buffer, body.byteOffset, body.byteLength); }
        else { return { text: String(body) }; }
        let bin = "";
        for (let j = 0; j < bytes.length; j += 0x8000) {
            bin += String.fromCharCode.apply(null, bytes.subarray(j, j + 0x8000));
        }
        return { base64: btoa(bin) };
    }

    function shouldProxy(url) {
        return (url.protocol === "http:" || url.protocol === "https:") && url.origin !== ASSET_ORIGIN;
    }

    window.fetch = async function (input, init) {
        let urlString = (typeof input === "string") ? input : (input && input.url) || String(input);
        let url;
        try { url = new URL(urlString, document.baseURI); } catch (e) { return originalFetch(input, init); }
        if (!bridge || !shouldProxy(url)) { return originalFetch(input, init); }

        init = init || {};
        const method = (init.method || (input && input.method) || "GET").toUpperCase();
        const headers = headersToObject(init.headers || (input && input.headers));
        const payload = await bodyToPayload(init.body, headers);
        const id = bridge.prepare(JSON.stringify({
            url: url.href,
            method: method,
            headers: headers,
            body: payload
        }));
        let raw;
        try {
            raw = await originalFetch(ASSET_ORIGIN + "/__proxy/" + id, { cache: "no-store" });
        } catch (e) {
            event({ type: "log", message: "WebView rejected proxied response for " + url.href + ": " + e });
            throw new TypeError("Failed to fetch (app proxy): " + e);
        }
        const err = raw.headers.get("x-w2e-error");
        if (err) { throw new TypeError("Failed to fetch: " + err); }
        const status = parseInt(raw.headers.get("x-w2e-status") || raw.status, 10);
        const finalUrl = raw.headers.get("x-w2e-url") || url.href;
        const statusText = raw.headers.get("x-w2e-status-text") || "";
        const outHeaders = new Headers();
        raw.headers.forEach((v, k) => { if (!k.startsWith("x-w2e-")) { outHeaders.append(k, v); } });
        const nullBody = [101, 204, 205, 304].includes(status);
        const buf = nullBody ? null : await raw.arrayBuffer();
        const resp = new Response(buf, { status: status, statusText: statusText, headers: outHeaders });
        Object.defineProperty(resp, "url", { value: finalUrl });
        Object.defineProperty(resp, "redirected", { value: finalUrl !== url.href });
        return resp;
    };

    // A few parsers use XMLHttpRequest directly; route those through the native layer too.
    const XHR = window.XMLHttpRequest;
    const origOpen = XHR.prototype.open;
    const origSend = XHR.prototype.send;
    const origSetHeader = XHR.prototype.setRequestHeader;
    XHR.prototype.open = function (method, urlStr, async, user, pass) {
        let url = null;
        try { url = new URL(urlStr, document.baseURI); } catch (e) { url = null; }
        this.__w2e = (bridge && url && shouldProxy(url)) ? { method: (method || "GET").toUpperCase(), url: url.href, headers: {}, async: async !== false } : null;
        if (!this.__w2e) { return origOpen.apply(this, arguments); }
        return undefined;
    };
    XHR.prototype.setRequestHeader = function (k, v) {
        if (this.__w2e) { this.__w2e.headers[k] = v; return undefined; }
        return origSetHeader.call(this, k, v);
    };
    XHR.prototype.send = function (body) {
        if (!this.__w2e) { return origSend.call(this, body); }
        const r = this.__w2e;
        let payload = null;
        if (body !== undefined && body !== null) {
            payload = { text: (body instanceof URLSearchParams) ? body.toString() : String(body) };
        }
        const id = bridge.prepare(JSON.stringify({ url: r.url, method: r.method, headers: r.headers, body: payload }));
        origOpen.call(this, "GET", ASSET_ORIGIN + "/__proxy/" + id, r.async);
        return origSend.call(this, null);
    };
})();
