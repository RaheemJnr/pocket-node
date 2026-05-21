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
        // The top margin pushes the "active" trigger band ~112px below
        // the viewport top (matches the sticky-nav height plus the
        // scroll-margin-top applied to headings in app/guide/page.tsx).
        // Without that offset, anchor navigation lands a heading
        // visually hidden under the nav and the observer never sees it.
        rootMargin: '-112px 0px -70% 0px',
        threshold: 0,
      },
    )

    const headings = toc
      .map((entry) => document.getElementById(entry.slug))
      .filter((el): el is HTMLElement => el !== null)
    headings.forEach((h) => observer.observe(h))
    return () => observer.disconnect()
  }, [toc])

  // When the user clicks a sidebar link the URL hash changes; anchor
  // navigation triggers a scroll which the IntersectionObserver above
  // will eventually catch up with. Optimistically setting `active` here
  // means the selected entry highlights immediately on click instead of
  // waiting for the observer's first emit (which can lag for the
  // jumped-to heading because of scroll-behavior: smooth).
  function handleClick(slug: string) {
    setActive(slug)
  }

  return (
    <nav className="sticky top-24 max-h-[calc(100vh-7rem)] overflow-y-auto pr-4">
      <p className="mb-4 font-doto text-xs font-black uppercase tracking-widest text-green">
        Contents
      </p>
      <ul className="flex flex-col gap-px">
        {toc.map((entry) => {
          const isActive = active === entry.slug
          // Strong left-border + slight background on the active entry
          // so the selection state reads clearly even before the user
          // scrolls. Indent H3 entries one notch so the hierarchy is
          // visible without a separate column.
          const base =
            'block border-l-2 py-1.5 font-doto text-sm font-semibold transition-colors'
          const indent = entry.level === 3 ? 'pl-7' : 'pl-3'
          const state = isActive
            ? 'border-green bg-green/10 text-green'
            : 'border-transparent text-white/60 hover:border-green/40 hover:text-white'
          return (
            <li key={entry.slug}>
              <Link
                href={`#${entry.slug}`}
                onClick={() => handleClick(entry.slug)}
                className={`${base} ${indent} ${state}`}
              >
                {entry.text}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
