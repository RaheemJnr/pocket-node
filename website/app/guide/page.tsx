import Link from 'next/link'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeSlug from 'rehype-slug'
import rehypeAutolinkHeadings from 'rehype-autolink-headings'
import { Navbar } from '@/components/Navbar'
import { Footer } from '@/components/Footer'
import { GuideSidebar } from '@/components/GuideSidebar'
import { loadGuide } from '@/lib/guide'

export const metadata = {
  title: 'User Guide — Pocket Node',
  description:
    'Install, backup, sync, send and receive, address book, multi-wallet, Nervos DAO, troubleshooting, FAQ, and the security model.',
}

/**
 * Renders the canonical user guide from docs/USER_GUIDE.md (synced
 * into content/user-guide.md by the prebuild script).
 *
 * Three-column layout on desktop: left sidebar TOC with scroll-spy,
 * main markdown body, narrow right gutter. Collapses to a single
 * column on mobile, with the TOC at the top.
 */
export default function GuidePage() {
  const { markdown, toc } = loadGuide()

  return (
    <>
      <Navbar />

      <header className="border-b border-green/20 bg-bg">
        <div className="mx-auto max-w-page px-6 py-12 md:px-12 md:py-16">
          <p className="mb-4 font-doto text-xs font-black uppercase tracking-widest text-green">
            Documentation
          </p>
          <h1 className="font-doto text-4xl font-bold uppercase leading-tight tracking-tight text-green md:text-6xl">
            User Guide
          </h1>
          <p className="mt-4 max-w-2xl font-doto text-sm font-semibold uppercase tracking-wide text-white/80 md:text-base">
            Install, backup, sync, send and receive, Address Book, multi-wallet, Nervos DAO, troubleshooting, and the security model.
          </p>
        </div>
      </header>

      <div className="mx-auto max-w-page px-6 py-12 md:px-12 md:py-16">
        <div className="grid grid-cols-1 gap-8 lg:grid-cols-[240px_minmax(0,1fr)] lg:gap-12">
          <aside className="lg:block">
            <GuideSidebar toc={toc} />
          </aside>

          <article className="prose prose-invert max-w-content prose-headings:font-doto prose-headings:uppercase prose-headings:tracking-tight prose-h1:hidden prose-h2:mt-16 prose-h2:border-t prose-h2:border-green/40 prose-h2:pt-10 prose-h2:text-green prose-h3:mt-10 prose-h3:text-green-light prose-p:font-sans prose-p:text-base prose-p:leading-relaxed prose-p:text-white/85 prose-li:text-white/85 prose-strong:text-white prose-a:text-green prose-a:no-underline hover:prose-a:underline prose-code:rounded prose-code:bg-surface prose-code:px-1.5 prose-code:py-0.5 prose-code:text-sm prose-code:text-green-light prose-code:before:content-none prose-code:after:content-none prose-pre:border prose-pre:border-green/30 prose-pre:bg-surface prose-table:font-sans prose-th:border prose-th:border-green/40 prose-th:bg-surface prose-th:p-3 prose-th:font-doto prose-th:uppercase prose-th:text-green prose-td:border prose-td:border-green/20 prose-td:p-3">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              rehypePlugins={[
                rehypeSlug,
                [
                  rehypeAutolinkHeadings,
                  {
                    behavior: 'wrap',
                    properties: {
                      className: 'anchor-link',
                    },
                  },
                ],
              ]}
            >
              {markdown}
            </ReactMarkdown>

            <hr className="my-12 border-green/20" />
            <p className="font-doto text-sm uppercase tracking-wide text-white/60">
              Source:{' '}
              <Link
                href="https://github.com/RaheemJnr/pocket-node/blob/main/docs/USER_GUIDE.md"
                target="_blank"
                rel="noopener noreferrer"
                className="text-green hover:underline"
              >
                docs/USER_GUIDE.md
              </Link>
              {' · '}
              <Link
                href="https://github.com/RaheemJnr/pocket-node/edit/main/docs/USER_GUIDE.md"
                target="_blank"
                rel="noopener noreferrer"
                className="text-green hover:underline"
              >
                Edit on GitHub
              </Link>
            </p>
          </article>
        </div>
      </div>

      <Footer />
    </>
  )
}
