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
