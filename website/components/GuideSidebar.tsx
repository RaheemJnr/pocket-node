'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import type { TocEntry } from '@/lib/guide'

/**
 * Left sidebar TOC with scroll-spy. Tracks which heading is currently
 * in view and highlights it. Uses IntersectionObserver against the
 * actual rendered headings rather than scroll position math so the
 * active entry stays stable across viewport-height changes and
 * variable section lengths.
 */
export function GuideSidebar({ toc }: { toc: TocEntry[] }) {
  const [active, setActive] = useState<string | null>(toc[0]?.slug ?? null)

  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        // Prefer the first entry that intersects; if multiple do at
        // once (long sections crossing the viewport), the topmost wins.
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
        if (visible[0]) setActive(visible[0].target.id)
      },
      {
        // The top margin pushes the "active" trigger band 80px below
        // the viewport top so a heading is considered active once it's
        // a bit into view, not just barely peeking.
        rootMargin: '-80px 0px -70% 0px',
        threshold: 0,
      },
    )

    const headings = toc
      .map((entry) => document.getElementById(entry.slug))
      .filter((el): el is HTMLElement => el !== null)
    headings.forEach((h) => observer.observe(h))
    return () => observer.disconnect()
  }, [toc])

  return (
    <nav className="sticky top-24 max-h-[calc(100vh-7rem)] overflow-y-auto pr-4">
      <p className="mb-4 font-doto text-xs font-black uppercase tracking-widest text-green">
        Contents
      </p>
      <ul className="flex flex-col gap-1">
        {toc.map((entry) => (
          <li key={entry.slug} className={entry.level === 3 ? 'pl-4' : ''}>
            <Link
              href={`#${entry.slug}`}
              className={`block py-1 font-doto text-sm font-semibold transition-colors ${
                active === entry.slug
                  ? 'text-green'
                  : 'text-white/60 hover:text-white'
              }`}
            >
              {entry.text}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}
