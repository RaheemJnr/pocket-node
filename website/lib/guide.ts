import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Build-time helpers for the /guide route.
 *
 * The markdown source lives at website/content/user-guide.md, synced
 * from docs/USER_GUIDE.md by `npm run sync-content` (or the prebuild
 * lifecycle script). Keeping the canonical doc under docs/ means the
 * GitHub repository render remains the source of truth that the
 * README and audit issues link to, while the website-side copy stays
 * portable across Vercel root-directory configurations.
 */

export interface TocEntry {
  level: 2 | 3
  text: string
  slug: string
}

export interface GuideContent {
  markdown: string
  toc: TocEntry[]
}

export function loadGuide(): GuideContent {
  const file = join(process.cwd(), 'content', 'user-guide.md')
  const markdown = readFileSync(file, 'utf8')
  return { markdown, toc: extractToc(markdown) }
}

/**
 * Pull H2 / H3 headings out of the markdown for the sidebar TOC.
 * Mirrors what rehype-slug will compute from the rendered HTML so
 * the anchor links in the sidebar match the anchors the markdown
 * renderer produces in the body.
 */
function extractToc(markdown: string): TocEntry[] {
  const lines = markdown.split('\n')
  const entries: TocEntry[] = []
  let inFence = false

  for (const line of lines) {
    // Skip headings that live inside fenced code blocks (e.g. shell
    // examples that contain "## comments").
    if (line.startsWith('```')) {
      inFence = !inFence
      continue
    }
    if (inFence) continue

    const h2 = /^##\s+(.+)$/.exec(line)
    if (h2) {
      const text = h2[1].trim()
      entries.push({ level: 2, text, slug: slugify(text) })
      continue
    }
    const h3 = /^###\s+(.+)$/.exec(line)
    if (h3) {
      const text = h3[1].trim()
      entries.push({ level: 3, text, slug: slugify(text) })
    }
  }
  return entries
}

/**
 * GitHub-style slug: lowercase, strip non-word characters, collapse
 * runs of whitespace and dashes to single dashes. Matches the default
 * rehype-slug behavior so the sidebar links resolve to the anchors
 * that react-markdown + rehype-slug emits.
 */
export function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\w\s-]/g, '')
    .trim()
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
}
