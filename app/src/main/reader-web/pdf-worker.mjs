// pdf.js's worker entry. Loads the same polyfills as the reader page first: older Android
// WebViews (Chromium < 119) lack Promise.withResolvers, which pdf.js uses in its worker.
import '/assets/reader/polyfills.js'
import '/assets/foliate/vendor/pdfjs/pdf.worker.mjs'
