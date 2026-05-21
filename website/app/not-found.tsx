import Link from 'next/link'
import { Navbar } from '@/components/Navbar'
import { Footer } from '@/components/Footer'

export const metadata = {
  title: 'Page not found — Pocket Node',
  description: 'The page you were looking for does not exist.',
}

/**
 * Branded 404 page. Without this, Next's default 404 renders unstyled
 * and clashes with the rest of the site. The dot-grid backdrop and
 * Doto headline keep the visual language consistent with the homepage.
 */
export default function NotFound() {
  return (
    <>
      <Navbar />

      <main className="relative flex min-h-[60vh] items-center justify-center overflow-hidden border-b border-green/20 bg-bg px-6 py-24 md:py-32">
        <div className="dot-grid-bg absolute inset-0 opacity-30" aria-hidden />
        <div className="relative text-center">
          <p className="mb-4 font-doto text-sm font-black uppercase tracking-widest text-green/80">
            404
          </p>
          <h1 className="mb-6 font-doto text-4xl font-bold uppercase leading-tight tracking-tight text-green md:text-6xl">
            Page not found
          </h1>
          <p className="mx-auto mb-10 max-w-md font-doto text-base font-semibold leading-relaxed text-white/80">
            The page you were looking for does not exist. It may have moved,
            or the link was never quite right.
          </p>
          <div className="flex flex-col items-stretch justify-center gap-3 sm:flex-row sm:items-center">
            <Link
              href="/"
              className="group inline-flex items-center justify-center gap-3 rounded-md border border-green bg-green/5 px-6 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green transition-colors hover:bg-green-deep hover:text-green-glow"
            >
              Back to home
              <span className="transition-transform group-hover:translate-x-1">→</span>
            </Link>
            <Link
              href="/guide"
              className="group inline-flex items-center justify-center gap-3 rounded-md border border-green/40 px-6 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green/80 transition-colors hover:border-green hover:text-green"
            >
              Open the user guide
              <span className="transition-transform group-hover:translate-x-1">→</span>
            </Link>
          </div>
        </div>
      </main>

      <Footer />
    </>
  )
}
