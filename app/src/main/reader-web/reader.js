/*
 * EpubForge reader, built on Readest's fork of foliate-js.
 *
 * The book is the story's downloaded chapters, one section per chapter, served by the app
 * from offline storage. The app supplies settings and the starting point through
 * window.ReaderBridge and gets position, page and tap events back.
 */
import './polyfills.js'
import '../foliate/view.js'

const R = window.ReaderBridge
R.progress('Reader engine loaded')

let view = null
let settings = JSON.parse(R.settings())
let story = null

const css = s => `
    @namespace epub "http://www.idpf.org/2007/ops";
    html {
        color-scheme: ${s.dark ? 'dark' : 'light'};
        -webkit-text-size-adjust: none;
        text-size-adjust: none;
    }
    html, body {
        background: transparent !important;
        color: ${s.fg} !important;
    }
    body {
        font-size: ${s.fontSize}px !important;
        line-height: ${s.lineHeight} !important;
        ${s.fontStack ? `font-family: ${s.fontStack} !important;` : ''}
        font-weight: ${s.fontWeight} !important;
        letter-spacing: ${s.letterSpacing}em !important;
        margin: 0 !important;
        padding: 0 !important;
    }
    body *:not(svg):not(svg *) {
        ${s.fontStack ? 'font-family: inherit !important;' : ''}
        color: inherit !important;
        background-color: transparent !important;
        line-height: inherit;
    }
    p, li, blockquote, dd {
        text-align: ${s.justify ? 'justify' : 'start'} !important;
        -webkit-hyphens: ${s.hyphenate ? 'auto' : 'manual'} !important;
        hyphens: ${s.hyphenate ? 'auto' : 'manual'} !important;
        widows: 2;
        orphans: 2;
    }
    p {
        margin-top: 0 !important;
        margin-bottom: ${s.paragraphSpacing}em !important;
        text-indent: ${s.indent}em !important;
    }
    h1, h2, h3, h4 {
        line-height: 1.3 !important;
        text-indent: 0 !important;
        margin: 0.4em 0 0.8em !important;
        text-align: start;
    }
    h1 { font-size: 1.45em !important; }
    h2 { font-size: 1.3em !important; }
    [align="center"], .center, center { text-align: center !important; text-indent: 0 !important; }
    a:link, a:visited { color: ${s.link} !important; }
    img, svg, video {
        max-width: 100% !important;
        height: auto !important;
        object-fit: contain;
        background-color: transparent !important;
    }
    hr { border: 0; border-top: 1px solid currentColor; opacity: 0.25; margin: 1.2em 0; }
    pre, code { white-space: pre-wrap !important; word-break: break-word; }
    table { border-collapse: collapse; max-width: 100%; margin: 0.6em 0; }
    td, th { border: 1px solid ${s.fg}40 !important; padding: 0.25em 0.5em; vertical-align: top; }
    blockquote { margin: 0.6em 1em !important; }
`

function applySettings() {
    const r = view.renderer
    document.documentElement.style.background = settings.bg
    document.body.style.background = settings.bg
    r.setAttribute('flow', settings.flow)
    r.setAttribute('gap', `${settings.gap}%`)
    r.setAttribute('margin-top', `${settings.marginTop}px`)
    r.setAttribute('margin-bottom', `${settings.marginBottom}px`)
    r.setAttribute('margin-left', '0px')
    r.setAttribute('margin-right', '0px')
    r.setAttribute('max-inline-size', '1200px')
    r.setAttribute('max-column-count', String(settings.columns || 1))
    r.toggleAttribute('animated', !!settings.animated)
    r.setStyles?.(css(settings))
}

function makeBook(data) {
    const sections = data.chapters.map((c, i) => ({
        id: `s${i}`,
        linear: 'yes',
        size: Math.max(1000, c.size || 1000),
        load: () => c.src,
        unload: () => {},
        createDocument: async () => {
            const text = await (await fetch(c.src)).text()
            return new DOMParser().parseFromString(text, 'text/html')
        },
    }))
    const parseHref = href => {
        const m = /^s(\d+)(?:#(.*))?$/.exec(String(href))
        return m ? { index: Number(m[1]), id: m[2] } : null
    }
    return {
        metadata: { title: data.title, author: data.author, language: data.language || 'en' },
        dir: 'ltr',
        rendition: { layout: 'reflowable' },
        sections,
        toc: data.chapters.map((c, i) => ({ label: c.title, href: `s${i}` })),
        resolveHref: href => {
            const p = parseHref(href)
            if (!p) return null
            return { index: p.index, anchor: p.id ? doc => doc.getElementById(p.id) : null }
        },
        splitTOCHref: href => {
            const p = parseHref(href)
            return p ? [`s${p.index}`, p.id ?? null] : [href, null]
        },
        getTOCFragment: (doc, id) => doc.getElementById(id),
        isExternal: href => /^(https?|mailto):/i.test(href) && !href.startsWith(location.origin),
    }
}

function attachDoc(doc) {
    doc.addEventListener('click', e => {
        if (e.defaultPrevented) return
        if (e.target.closest?.('a[href]')) return
        const sel = doc.getSelection?.()
        if (sel && !sel.isCollapsed) return
        const frame = doc.defaultView?.frameElement
        const left = frame ? frame.getBoundingClientRect().left : 0
        tap((left + e.clientX) / window.innerWidth)
    })
}

function tap(x) {
    R.tap(x < 0.3 ? 'left' : x > 0.7 ? 'right' : 'center')
}

let lastReport = ''
function report(detail) {
    const r = view.renderer
    const viewSize = r.viewSize || 0
    const atEnd = viewSize > 0 && r.end >= viewSize - 8
    const msg = JSON.stringify({
        index: detail.index ?? -1,
        fraction: Number(detail.fraction ?? 0),
        page: r.page ?? 0,
        pages: r.pages ?? 0,
        atEnd,
        bookFraction: Number(view.lastLocation?.fraction ?? 0),
    })
    if (msg === lastReport) return
    lastReport = msg
    R.relocate(msg)
}

// Android lays the WebView out (and hides the system bars) while the reader opens, so its size can
// still be changing. Opening the book at a tiny height made foliate split each chapter into hundreds
// of slivers. Wait until the size is real and steady first.
function waitForStableSize(maxMs = 4000) {
    return new Promise(resolve => {
        const t0 = performance.now()
        let last = '', same = 0
        const tick = () => {
            const key = window.innerWidth + 'x' + window.innerHeight
            same = key === last ? same + 1 : 0
            last = key
            const ok = window.innerHeight >= 200 && window.innerWidth >= 200 && same >= 6
            if (ok || performance.now() - t0 > maxMs) resolve()
            else requestAnimationFrame(tick)
        }
        requestAnimationFrame(tick)
    })
}

let lastPos = null
let relayoutTimer = 0
let lastSize = ''
function relayout() {
    clearTimeout(relayoutTimer)
    relayoutTimer = setTimeout(async () => {
        const size = window.innerWidth + 'x' + window.innerHeight
        if (!view?.renderer || size === lastSize) return
        lastSize = size
        const pos = lastPos
        try {
            view.renderer.render?.()
            if (pos) await view.renderer.goTo({ index: pos.index, anchor: pos.fraction })
        } catch (e) { console.warn('relayout', e) }
    }, 150)
}

async function main() {
    story = JSON.parse(R.story())
    view = document.createElement('foliate-view')
    // Explicit pixel size as a belt-and-braces fallback to the stylesheet; kept in sync on resize.
    const fit = () => { view.style.width = innerWidth + 'px'; view.style.height = innerHeight + 'px' }
    fit()
    window.addEventListener('resize', fit)
    document.body.append(view)
    view.addEventListener('load', e => attachDoc(e.detail.doc))
    view.addEventListener('external-link', e => {
        e.preventDefault()
        R.openLink(e.detail.href)
    })
    document.addEventListener('click', e => tap(e.clientX / window.innerWidth))
    if (story.mode === 'file') return openFile()
    if (!story.chapters.length) {
        R.error('No downloaded chapters to read yet.')
        return
    }
    R.progress('Opening book')
    await waitForStableSize()
    lastSize = window.innerWidth + 'x' + window.innerHeight
    await view.open(makeBook(story))
    view.renderer.addEventListener('relocate', e => {
        lastPos = { index: e.detail.index, fraction: Number(e.detail.fraction ?? 0) }
        report(e.detail)
    })
    window.addEventListener('resize', relayout)
    applySettings()
    const start = story.start || { index: 0, fraction: 0 }
    R.progress('Opening chapter')
    await view.renderer.goTo({ index: start.index, anchor: start.fraction > 0 ? start.fraction : 0 })
    R.ready()
    diag('ready')
    setTimeout(() => diag('after2s'), 2000)
}

// ---------- imported ebook files (EPUB, MOBI/AZW3, FB2, CBZ, PDF)

function flattenToc(items, depth = 0, out = []) {
    for (const t of items || []) {
        out.push({ label: (t.label || '').trim(), href: t.href || '', depth })
        if (t.subitems?.length) flattenToc(t.subitems, depth + 1, out)
    }
    return out
}

let lastBookReport = ''
function reportBook(loc) {
    const r = view.renderer
    const msg = JSON.stringify({
        mode: 'file',
        index: loc.section?.current ?? lastPos?.index ?? 0,
        fraction: Number(lastPos?.fraction ?? 0),
        page: r.page ?? 0,
        pages: r.pages ?? 0,
        atEnd: false,
        bookFraction: Number(loc.fraction ?? 0),
        cfi: loc.cfi || '',
        tocLabel: loc.tocItem?.label?.trim() || '',
        tocHref: loc.tocItem?.href || '',
    })
    if (msg === lastBookReport) return
    lastBookReport = msg
    R.relocate(msg)
}

async function blobToDataUrl(blob) {
    return new Promise(resolve => {
        const fr = new FileReader()
        fr.onload = () => resolve(String(fr.result))
        fr.onerror = () => resolve('')
        fr.readAsDataURL(blob)
    })
}

async function openFile() {
    R.progress('Loading book')
    const res = await fetch(story.src)
    if (!res.ok) throw new Error(`Couldn't read the book file (HTTP ${res.status})`)
    const file = new File([await res.blob()], story.name, { type: story.type || '' })
    await waitForStableSize()
    lastSize = window.innerWidth + 'x' + window.innerHeight
    R.progress('Opening book')
    try {
        await view.open(file)
    } catch (e) {
        if (e?.name === 'UnsupportedTypeError' || /not supported/i.test(e?.message || '')) throw new Error('This file type is not supported.')
        throw e
    }
    view.renderer.addEventListener('relocate', e => {
        lastPos = { index: e.detail.index, fraction: Number(e.detail.fraction ?? 0) }
    })
    view.addEventListener('relocate', e => reportBook(e.detail))
    window.addEventListener('resize', relayout)
    applySettings()

    const book = view.book
    try { R.toc(JSON.stringify(flattenToc(book.toc))) } catch (e) { console.warn('toc', e) }
    // Title, author and cover for formats the app can't parse itself (MOBI, AZW3, CBZ, PDF).
    if (story.wantMeta) {
        try {
            const m = book.metadata || {}
            const name = x => typeof x === 'string' ? x : (x?.name ? (typeof x.name === 'string' ? x.name : Object.values(x.name)[0]) : (x && Object.values(x)[0]) || '')
            const title = typeof m.title === 'string' ? m.title : (m.title ? Object.values(m.title)[0] : '')
            const authors = [].concat(m.author || []).map(name).filter(Boolean).join(', ')
            const cover = await book.getCover?.()
            R.bookInfo(JSON.stringify({ title: title || '', author: authors }), cover ? await blobToDataUrl(cover) : '')
        } catch (e) { console.warn('meta', e) }
    }

    R.progress('Opening book')
    const start = story.start || {}
    let opened = false
    if (start.cfi) {
        try { await view.init({ lastLocation: start.cfi }); opened = true } catch (e) { console.warn('cfi', e) }
    }
    if (!opened && start.fraction > 0) {
        try { await view.goToFraction(start.fraction); opened = true } catch (e) { console.warn('fraction', e) }
    }
    if (!opened) await view.init({ showTextStart: false })
    R.ready()
    diag('ready')
}

// Layout diagnostics, written to the app log (logcat tag "Reader").
function diag(tag) {
    try {
        const r = view.renderer
        const rect = el => { if (!el) return null; const b = el.getBoundingClientRect(); return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)] }
        const frames = []
        const walk = root => { if (!root) return; for (const f of root.querySelectorAll('iframe')) frames.push(f); for (const e of root.querySelectorAll('*')) if (e.shadowRoot) walk(e.shadowRoot) }
        walk(document); walk(view.shadowRoot); walk(r.shadowRoot)
        const f = frames[0]
        const d = f?.contentDocument
        const de = d?.documentElement
        const cs = de ? d.defaultView.getComputedStyle(de) : null
        const p = d?.querySelector('p')
        console.log('DIAG ' + tag + ' ' + JSON.stringify({
            win: [innerWidth, innerHeight, devicePixelRatio], vv: [visualViewport?.width, visualViewport?.height],
            view: rect(view), renderer: rect(r), size: r.size, viewSize: r.viewSize, page: r.page, pages: r.pages,
            frames: frames.length, frame: rect(f), frameAttr: f ? [f.style.width, f.style.height] : null,
            de: de ? [de.scrollWidth, de.scrollHeight, de.clientWidth, de.clientHeight] : null,
            css: cs ? [cs.columnWidth, cs.columnGap, cs.height, cs.width, cs.writingMode, cs.fontSize, cs.lineHeight, cs.boxSizing, cs.padding] : null,
            bodyCss: d?.body ? [d.defaultView.getComputedStyle(d.body).height, d.defaultView.getComputedStyle(d.body).fontSize] : null,
            p: rect(p), pText: p?.textContent?.slice(0, 30), deStyle: de?.getAttribute('style')?.slice(0, 400),
        }))
    } catch (e) { console.log('DIAG failed ' + e) }
}

window.reader = {
    diag,
    next: () => view?.next(),
    prev: () => view?.prev(),
    goToChapter: (index, fraction) => view?.renderer?.goTo({ index, anchor: fraction || 0 }),
    nextChapter: () => view?.renderer?.nextSection?.(),
    prevChapter: () => view?.renderer?.prevSection?.(),
    relayout: () => { lastSize = ''; relayout() },
    goToFraction: f => view?.goToFraction(f),
    goToHref: href => view?.goTo(href),
    apply: () => { settings = JSON.parse(R.settings()); if (view) applySettings() },
}

main().catch(err => R.error('Could not open the story: ' + (err?.stack || err?.message || err)))
