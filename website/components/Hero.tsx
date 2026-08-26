import Link from 'next/link'

/**
 * Hero variant A (the chosen variant for v2.0.0). Two-line vertical
 * stack in Doto, brand-green accent on the second line for the rhetorical
 * weight, then a short sovereignty pitch and two link-with-arrow CTAs.
 *
 * The layout follows ckba.build's hero structure (left-aligned headline
 * stack, max-width sub-copy, button row) but drops the full-bleed
 * background image for a cleaner type-first treatment that scales without
 * needing per-breakpoint imagery to produce.
 */
export function Hero() {
  return (
    <section className="relative w-full overflow-hidden border-b border-green/20">
      {/* Dot-grid texture strip at the top, matching ckba's about-section header. */}
      <div className="dot-grid-bg absolute inset-x-0 top-0 h-16 opacity-40" aria-hidden />

      <div className="relative mx-auto max-w-page px-6 py-24 md:px-12 md:py-32">
        <div className="mb-8 md:mb-12">
          <span className="inline-flex items-center gap-2 border border-green/40 px-3 py-1.5 font-doto text-xs font-black uppercase tracking-widest text-green">
            <span className="block h-2 w-2 bg-green" aria-hidden />
            v1.7 shipped · open source
          </span>
        </div>

        <h1 className="mb-4 font-doto text-5xl font-normal uppercase leading-[0.98] text-white md:mb-6 md:text-7xl lg:text-8xl">
          Your phone.
        </h1>
        <h1 className="mb-8 font-doto text-5xl font-normal uppercase leading-[0.98] tracking-tight text-green md:mb-10 md:text-7xl lg:text-8xl">
          The node.
        </h1>

        <p className="mb-12 max-w-xl font-doto text-base font-semibold uppercase leading-relaxed tracking-wide text-white md:text-lg lg:text-xl">
          The first Android wallet that runs a full CKB light client on-device.
          Your keys. Your sync. No middleman.
        </p>

        <div className="flex flex-col items-start gap-3 sm:flex-row sm:items-center">
          <Link
            href="https://play.google.com/store/apps/details?id=com.rjnr.pocketnode"
            target="_blank"
            rel="noopener noreferrer"
            className="group inline-flex items-center gap-3 rounded-md border border-green bg-green/5 px-6 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green transition-colors hover:bg-green-deep hover:text-green-glow"
          >
            <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor" aria-hidden>
              <path d="M3.609 1.814 13.792 12 3.61 22.186a.996.996 0 0 1-.61-.92V2.734a1 1 0 0 1 .609-.92zM14.5 12.707l2.302 2.302-10.937 6.333 8.635-8.635zm3.31-3.31 2.79 1.615c.73.42.73 1.545 0 1.966l-2.79 1.615L15.207 12l2.603-2.603zM5.865 2.658l10.937 6.333-2.302 2.302-8.635-8.635z"/>
            </svg>
            Google Play
          </Link>
          <Link
            href="/download"
            // The /download route resolves the latest GitHub release's
            // .apk asset at request time and 302-redirects to it. The
            // browser kicks off a normal file download without leaving
            // the site visually.
            className="group inline-flex items-center gap-3 rounded-md border border-green bg-green/5 px-6 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green transition-colors hover:bg-green-deep hover:text-green-glow"
          >
            Download APK
            <span className="transition-transform group-hover:translate-x-1">→</span>
          </Link>
          <Link
            href="/guide"
            className="group inline-flex items-center gap-3 px-6 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green transition-colors hover:text-green-glow"
          >
            Read the guide
            <span className="transition-transform group-hover:translate-x-1">→</span>
          </Link>
        </div>
      </div>
    </section>
  )
}
