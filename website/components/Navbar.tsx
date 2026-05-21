import Link from 'next/link'
import Image from 'next/image'

/**
 * Sticky navbar matching the ckba.build pattern: thin border-bottom in the
 * brand color, all-caps Doto links, link-with-arrow CTA on the right. The
 * left brand mark uses the existing app icon so the site reads as the same
 * product family as the APK.
 */
export function Navbar() {
  return (
    <nav className="sticky top-0 z-50 w-full border-b border-green/40 bg-bg/80 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-page items-center justify-between px-4 md:h-20 md:px-0">
        <div className="flex items-center md:border-r md:border-green/40 md:py-5 md:px-6">
          <Link href="/" className="flex items-center gap-3">
            <Image
              src="/icon.png"
              alt="Pocket Node"
              width={36}
              height={36}
              className="rounded-md"
              priority
            />
            <span className="font-doto text-lg font-black uppercase tracking-tight text-green">
              Pocket Node
            </span>
          </Link>
        </div>

        <div className="hidden items-center md:flex">
          <NavLink href="#features">Features</NavLink>
          <NavLink href="#security">Security</NavLink>
          <NavLink href="/guide">Guide</NavLink>
          <Link
            href="https://github.com/RaheemJnr/pocket-node/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
            className="group flex h-20 items-center gap-3 border-l border-green/40 px-8 font-doto text-sm font-black uppercase leading-none tracking-wide text-green transition-colors hover:bg-green/10"
          >
            Download APK
            <span className="text-green transition-transform group-hover:translate-x-1">→</span>
          </Link>
        </div>
      </div>
    </nav>
  )
}

function NavLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="flex h-20 items-center px-5 font-doto text-sm font-black uppercase leading-none tracking-wide text-green/80 transition-colors hover:text-green-glow"
    >
      {children}
    </Link>
  )
}
