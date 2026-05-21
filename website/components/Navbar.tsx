'use client'

import Link from 'next/link'
import Image from 'next/image'
import { useEffect, useState } from 'react'

/**
 * Sticky navbar matching the ckba.build pattern: thin border-bottom in
 * the brand color, all-caps Doto links, link-with-arrow CTA on the right.
 *
 * Below md, the inline links collapse and a hamburger toggle opens a
 * full-screen drawer with the same destinations. The drawer is closed
 * by tapping a link, the close button, or the Esc key. Drawer state
 * is local to the navbar; the rest of the page stays static.
 */
export function Navbar() {
  const [open, setOpen] = useState(false)

  // Esc closes the drawer. Doing this from the navbar keeps the
  // keyboard behavior next to the state that drives it.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  // Lock body scroll while the drawer is open so the user doesn't
  // accidentally scroll the page underneath.
  useEffect(() => {
    document.body.style.overflow = open ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  return (
    <>
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
            <NavLink href="/#features">Features</NavLink>
            <NavLink href="/#security">Security</NavLink>
            <NavLink href="/guide">Guide</NavLink>
            <NavLink href="https://pocket-node-learn-ckb.vercel.app/" external>
              Learn CKB
            </NavLink>
            <Link
              href="/download"
              className="group flex h-20 items-center gap-3 border-l border-green/40 px-8 font-doto text-sm font-black uppercase leading-none tracking-wide text-green transition-colors hover:bg-green/10"
            >
              Download APK
              <span className="text-green transition-transform group-hover:translate-x-1">→</span>
            </Link>
          </div>

          <button
            type="button"
            onClick={() => setOpen(true)}
            aria-label="Open menu"
            aria-expanded={open}
            className="flex h-10 w-10 items-center justify-center border border-green/40 text-green transition-colors hover:bg-green/10 md:hidden"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
              <line x1="3" y1="6" x2="21" y2="6" />
              <line x1="3" y1="12" x2="21" y2="12" />
              <line x1="3" y1="18" x2="21" y2="18" />
            </svg>
          </button>
        </div>
      </nav>

      {/* Mobile drawer. Rendered outside the sticky nav so it can take the
          full viewport without inheriting any positioning context. */}
      {open && (
        <div className="fixed inset-0 z-[60] flex flex-col bg-bg md:hidden">
          <div className="flex h-16 items-center justify-between border-b border-green/40 px-4">
            <Link href="/" onClick={() => setOpen(false)} className="flex items-center gap-3">
              <Image src="/icon.png" alt="" width={36} height={36} className="rounded-md" />
              <span className="font-doto text-lg font-black uppercase tracking-tight text-green">
                Pocket Node
              </span>
            </Link>
            <button
              type="button"
              onClick={() => setOpen(false)}
              aria-label="Close menu"
              className="flex h-10 w-10 items-center justify-center border border-green/40 text-green transition-colors hover:bg-green/10"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>

          <div className="flex flex-col gap-2 p-6">
            <DrawerLink href="/#features" onClick={() => setOpen(false)}>
              Features
            </DrawerLink>
            <DrawerLink href="/#security" onClick={() => setOpen(false)}>
              Security
            </DrawerLink>
            <DrawerLink href="/guide" onClick={() => setOpen(false)}>
              User Guide
            </DrawerLink>
            <DrawerLink
              href="https://pocket-node-learn-ckb.vercel.app/"
              onClick={() => setOpen(false)}
              external
            >
              Learn CKB
            </DrawerLink>
            <DrawerLink href="/privacy" onClick={() => setOpen(false)}>
              Privacy
            </DrawerLink>
            <DrawerLink
              href="https://github.com/RaheemJnr/pocket-node"
              onClick={() => setOpen(false)}
              external
            >
              GitHub
            </DrawerLink>
          </div>

          <div className="mt-auto border-t border-green/40 p-6">
            <Link
              href="/download"
              onClick={() => setOpen(false)}
              className="group flex items-center justify-center gap-3 rounded-md border border-green bg-green/5 px-6 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green transition-colors hover:bg-green-deep hover:text-green-glow"
            >
              Download APK
              <span className="transition-transform group-hover:translate-x-1">→</span>
            </Link>
          </div>
        </div>
      )}
    </>
  )
}

function NavLink({
  href,
  external,
  children,
}: {
  href: string
  external?: boolean
  children: React.ReactNode
}) {
  return (
    <Link
      href={href}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      className="flex h-20 items-center px-5 font-doto text-sm font-black uppercase leading-none tracking-wide text-green/80 transition-colors hover:text-green-glow"
    >
      {children}
    </Link>
  )
}

function DrawerLink({
  href,
  onClick,
  external,
  children,
}: {
  href: string
  onClick: () => void
  external?: boolean
  children: React.ReactNode
}) {
  return (
    <Link
      href={href}
      onClick={onClick}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      className="flex items-center justify-between border-l-2 border-transparent px-4 py-4 font-doto text-base font-bold uppercase tracking-wide text-white transition-colors hover:border-green hover:bg-green/10 hover:text-green"
    >
      {children}
      <span className="text-green/60">→</span>
    </Link>
  )
}
