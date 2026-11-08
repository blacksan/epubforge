// Polyfills for older Android WebViews (the same ones Readest ships for foliate-js).
Object.groupBy ??= (iterable, fn) => {
    const obj = Object.create(null); let i = 0
    for (const v of iterable) { const k = fn(v, i++); (obj[k] ??= []).push(v) }
    return obj
}
Map.groupBy ??= (iterable, fn) => {
    const map = new Map(); let i = 0
    for (const v of iterable) { const k = fn(v, i++); const l = map.get(k); l ? l.push(v) : map.set(k, [v]) }
    return map
}
Promise.withResolvers ??= function () {
    let resolve, reject
    const promise = new Promise((a, b) => { resolve = a; reject = b })
    return { promise, resolve, reject }
}
if (!Array.prototype.findLast) {
    Array.prototype.findLast = function (fn, thisArg) {
        for (let i = this.length - 1; i >= 0; i--) if (fn.call(thisArg, this[i], i, this)) return this[i]
    }
}
if (!Array.prototype.findLastIndex) {
    Array.prototype.findLastIndex = function (fn, thisArg) {
        for (let i = this.length - 1; i >= 0; i--) if (fn.call(thisArg, this[i], i, this)) return i
        return -1
    }
}
if (!Array.prototype.at) {
    Array.prototype.at = function (n) { n = Math.trunc(n) || 0; if (n < 0) n += this.length; return this[n] }
}
if (!String.prototype.at) {
    String.prototype.at = function (n) { n = Math.trunc(n) || 0; if (n < 0) n += this.length; return this[n] }
}
if (!Array.prototype.toSorted) {
    Array.prototype.toSorted = function (fn) { return [...this].sort(fn) }
}
if (!Array.prototype.toReversed) {
    Array.prototype.toReversed = function () { return [...this].reverse() }
}
if (typeof structuredClone !== 'function') {
    globalThis.structuredClone = v => JSON.parse(JSON.stringify(v))
}
