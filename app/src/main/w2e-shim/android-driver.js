/*
 * EpubForge driver: runs WebToEpub's parsers without its popup UI.
 *
 *   W2E.analyse(url, opts)   story details + chapter list (one request to the site)
 *   W2E.download(url, opts)  analyse once, then download chapters one at a time. The next
 *                            chapter comes from the app (W2EBridge.nextChapter()), so the
 *                            queue can change, pause or stop while downloading.
 *
 * Each downloaded chapter is cleaned by WebToEpub exactly as it would be for an EPUB, its
 * images fetched, and the result handed to the app as XHTML + image files.
 */
(function () {
    "use strict";
    const event = window.__w2eEvent || function () {};
    const bridge = window.W2EBridge;
    const state = { retries: new Map(), maxRetries: 4, errors: [], running: false, cancelled: false };

    try {
        if (localStorage.getItem("skipChaptersThatFailFetch") === null) {
            localStorage.setItem("skipChaptersThatFailFetch", "true");
        }
    } catch (e) { /* ignore */ }

    function msgText(msg) {
        try { return ErrorLog.getMsgText(msg); } catch (e) { return String(msg && msg.message || msg); }
    }

    function patchErrorLog() {
        if (typeof ErrorLog === "undefined" || ErrorLog.__w2ePatched) { return; }
        ErrorLog.__w2ePatched = true;
        ErrorLog.showErrorMessage = function (msg) {
            const text = msgText(msg);
            if (msg && typeof msg.retryAction === "function") {
                const key = msg.blockurl || text;
                const n = (state.retries.get(key) || 0) + 1;
                state.retries.set(key, n);
                if (n <= state.maxRetries) {
                    event({ type: "warn", message: "Retrying (" + n + "/" + state.maxRetries + "): " + text });
                    setTimeout(() => msg.retryAction(), 3000 * n);
                } else {
                    event({ type: "warn", message: "Giving up: " + text });
                    if (typeof msg.cancelAction === "function") { msg.cancelAction(); }
                }
                return;
            }
            state.errors.push(text);
            event({ type: "error", message: text });
        };
        ErrorLog.showLogToUser = function () {};
    }

    // WebToEpub measures images by loading them into <img>; never let that hang a chapter.
    function patchImageCollector() {
        if (typeof ImageCollector === "undefined" || ImageCollector.prototype.__w2ePatched) { return; }
        ImageCollector.prototype.__w2ePatched = true;
        const original = ImageCollector.prototype.getImageDimensions;
        ImageCollector.prototype.getImageDimensions = function (info) {
            return new Promise((resolve, reject) => {
                let settled = false;
                const fallback = setTimeout(async () => {
                    if (settled) { return; }
                    settled = true;
                    try {
                        const blob = new Blob([new Uint8Array(info.arraybuffer)], { type: info.mediaType });
                        const bmp = await createImageBitmap(blob);
                        info.width = bmp.width;
                        info.height = bmp.height;
                        resolve(bmp);
                    } catch (e) {
                        info.width = 1600;
                        info.height = 1200;
                        reject(e);
                    }
                }, 10000);
                original.call(this, info).then(
                    (r) => { if (!settled) { settled = true; clearTimeout(fallback); resolve(r); } },
                    (e) => { if (!settled) { settled = true; clearTimeout(fallback); reject(e); } }
                );
            });
        };
    }

    // Ask for images when fetching images. The default Accept header (a browser's, for pages) makes
    // some image hosts, Imgur in particular, answer with an HTML viewer page instead of the picture;
    // WebToEpub then fetches the same URL again and stores HTML as the "image".
    const IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";
    const IMAGE_PATH = /\.(png|jpe?g|gif|webp|avif|bmp|svg)$/i;
    function patchImageFetches() {
        if (typeof HttpClient === "undefined" || HttpClient.__w2eImagePatched) { return; }
        HttpClient.__w2eImagePatched = true;
        const original = HttpClient.wrapFetch;
        HttpClient.wrapFetch = function (url, wrapOptions) {
            let path = "";
            try { path = new URL(url).pathname; } catch (e) { /* relative or invalid: leave as is */ }
            const forImage = (typeof FetchImageErrorHandler !== "undefined" && wrapOptions?.errorHandler instanceof FetchImageErrorHandler)
                || IMAGE_PATH.test(path);
            if (forImage) {
                wrapOptions = wrapOptions || {};
                const base = wrapOptions.fetchOptions || HttpClient.makeOptions();
                wrapOptions.fetchOptions = Object.assign({}, base, {
                    headers: Object.assign({}, base.headers || {}, { "Accept": IMAGE_ACCEPT }),
                });
            }
            return original.call(this, url, wrapOptions);
        };
    }

    function val(id) {
        const el = document.getElementById(id);
        return el ? String(el.value !== undefined ? el.value : el.textContent || "").trim() : "";
    }

    function applyPreferences(opts) {
        const prefs = main.getUserPreferences();
        if (!prefs) { return; }
        if (typeof opts.includeImages === "boolean" && prefs.skipImages) {
            prefs.skipImages.value = !opts.includeImages;
        }
        if (typeof opts.includeAuthorNotes === "boolean" && prefs.removeAuthorNotes) {
            prefs.removeAuthorNotes.value = !opts.includeAuthorNotes;
        }
        if (prefs.skipChaptersThatFailFetch) { prefs.skipChaptersThatFailFetch.value = true; }
        // Images are stored as files for the reader; keep them as plain <img> tags.
        if (prefs.useSvgForImages) { prefs.useSvgForImages.value = false; }
    }

    function normUrl(u) {
        return String(u || "").trim().replace(/#.*$/, "").replace(/\/+$/, "").toLowerCase()
            .replace("://royalroad.com", "://www.royalroad.com");
    }

    async function analyseStory(url, opts) {
        patchErrorLog();
        patchImageCollector();
        patchImageFetches();
        state.errors = [];
        state.retries = new Map();
        state.maxRetries = opts.maxRetries || 4;
        applyPreferences(opts);
        document.getElementById("startingUrlInput").value = url;
        await main.onLoadAndAnalyseButtonClick();
        const parser = main.getCurrentParser();
        if (!parser) {
            throw new Error(state.errors[0] || "This site isn't supported by WebToEpub.");
        }
        const pages = [...parser.getPagesToFetch().values()];
        if (pages.length === 0) {
            throw new Error(state.errors[0] || "No chapters were found at this link.");
        }
        applyPreferences(opts);
        return { parser: parser, pages: pages };
    }

    function storyInfo(pages) {
        return {
            type: "story",
            title: val("titleInput"),
            author: /^<?unknown>?$/i.test(val("authorInput").trim()) ? "" : val("authorInput"),
            language: val("languageInput"),
            cover: val("coverImageUrlInput"),
            description: val("descriptionInput"),
            subject: val("subjectInput"),
            chapters: pages.map(p => ({
                url: String(p.sourceUrl || ""),
                title: String(p.title || "").trim()
            }))
        };
    }

    async function analyse(url, opts) {
        if (state.running) { event({ type: "failed", message: "Busy" }); return; }
        state.running = true;
        try {
            event({ type: "phase", phase: "analysing" });
            const { pages } = await analyseStory(url, opts || {});
            event(storyInfo(pages));
            event({ type: "done" });
        } catch (err) {
            event({ type: "failed", message: msgText(err) });
        } finally {
            state.running = false;
        }
    }

    function bytesToBase64(bytes) {
        let bin = "";
        for (let j = 0; j < bytes.length; j += 0x8000) {
            bin += String.fromCharCode.apply(null, bytes.subarray(j, j + 0x8000));
        }
        return btoa(bin);
    }

    function sendImage(chapterUrl, name, arraybuffer) {
        const bytes = new Uint8Array(arraybuffer);
        bridge.fileBegin(JSON.stringify({ kind: "image", chapter: chapterUrl, name: name }), "image", bytes.length);
        const CHUNK = 384 * 1024;
        for (let i = 0; i < bytes.length; i += CHUNK) {
            bridge.fileChunk(bytesToBase64(bytes.subarray(i, Math.min(i + CHUNK, bytes.length))));
        }
        bridge.fileEnd();
    }

    function baseName(path) {
        return String(path || "").split("/").pop();
    }

    /** Downloads one chapter through WebToEpub and hands the result to the app. */
    async function downloadChapter(parser, wp) {
        await parser.addParsersToPages([wp]);
        parser.imageCollector.reset();
        delete wp.error;
        await parser.fetchWebPageContent(wp);
        if (wp.error != null) {
            throw new Error(msgText(wp.error));
        }
        if (wp.rawDom == null) {
            throw new Error("No content was downloaded.");
        }
        const content = wp.parser.convertRawDomToContent(wp);
        if (content == null) {
            throw new Error("WebToEpub found no chapter text on the page.");
        }

        // Images: give each a stable file name and point the chapter at it.
        const images = parser.imageCollector.imagesToPackInEpub().filter(i => i.arraybuffer);
        const names = new Map();
        for (const info of images) {
            names.set(baseName(info.getZipHref()), info);
        }
        const XLINK = "http://www.w3.org/1999/xlink";
        content.querySelectorAll("img[src]").forEach(el => {
            const b = baseName(el.getAttribute("src"));
            if (names.has(b)) { el.setAttribute("src", "@@IMG@@/" + b); }
        });
        content.querySelectorAll("image").forEach(el => {
            const v = el.getAttribute("href") || el.getAttributeNS(XLINK, "href");
            const b = baseName(v);
            if (names.has(b)) {
                el.setAttribute("href", "@@IMG@@/" + b);
                el.setAttributeNS(XLINK, "xlink:href", "@@IMG@@/" + b);
            }
        });
        for (const [name, info] of names) {
            sendImage(wp.sourceUrl, name, info.arraybuffer);
        }
        const xhtml = new XMLSerializer().serializeToString(content);
        const title = String(wp.title || "").trim();
        bridge.chapter(JSON.stringify({ url: String(wp.sourceUrl), title: title, images: names.size }), xhtml);
        delete wp.rawDom;
    }

    async function download(url, opts) {
        if (state.running) { event({ type: "failed", message: "Busy" }); return; }
        state.running = true;
        state.cancelled = false;
        opts = opts || {};
        try {
            event({ type: "phase", phase: "analysing" });
            const { parser, pages } = await analyseStory(url, opts);
            event(storyInfo(pages));
            const byUrl = new Map(pages.map(p => [normUrl(p.sourceUrl), p]));
            event({ type: "phase", phase: "fetching" });
            while (!state.cancelled) {
                const next = bridge.nextChapter();
                if (!next) { break; }
                const wp = byUrl.get(normUrl(next));
                if (!wp) {
                    event({ type: "chapterFailed", url: next, message: "This chapter is no longer listed on the site." });
                    continue;
                }
                event({ type: "chapterStart", url: next });
                try {
                    await downloadChapter(parser, wp);
                } catch (err) {
                    delete wp.rawDom;
                    event({ type: "chapterFailed", url: next, message: msgText(err) });
                }
            }
            event({ type: "done" });
        } catch (err) {
            event({ type: "failed", message: msgText(err) });
        } finally {
            state.running = false;
        }
    }

    function cancel() {
        state.cancelled = true;
        try { util.getSleepController().abort(); } catch (e) { /* ignore */ }
    }

    window.W2E = { analyse: analyse, download: download, cancel: cancel };

    window.addEventListener("load", () => {
        patchErrorLog();
        patchImageCollector();
        patchImageFetches();
        setTimeout(() => {
            let version = "";
            try { version = chrome.runtime.getManifest().version || ""; } catch (e) { /* ignore */ }
            event({ type: "ready", version: version });
        }, 300);
    });
})();
