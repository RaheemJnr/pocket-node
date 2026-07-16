import Link from 'next/link'
import Image from 'next/image'

/**
 * Footer mirrors ckba's structure: thin border-top, brand mark on the
 * left, social icons, and a primary CTA on the right. Two columns of
 * resource links below for documentation and ecosystem references.
 */
export function Footer() {
  return (
    <footer className="border-t border-green/40 bg-bg">
      <div className="mx-auto max-w-page px-6 py-12 md:px-12">
        <div className="grid grid-cols-1 gap-12 md:grid-cols-4">
          <div className="md:col-span-2">
            <div className="mb-4 flex items-center gap-3">
              <Image
                src="/icon.png"
                alt="Pocket Node"
                width={40}
                height={40}
                className="rounded-md"
              />
              <span className="font-doto text-xl font-black uppercase tracking-tight text-green">
                Pocket Node
              </span>
            </div>
            <p className="max-w-sm font-doto text-sm font-semibold leading-relaxed text-white/80">
              Self-custody CKB wallet with an embedded light client.
              Your keys. Your node. Your sovereignty.
            </p>
          </div>

          <FooterColumn title="Documentation">
            <FooterLink href="/guide">User Guide</FooterLink>
            <FooterLink
              href="https://github.com/RaheemJnr/pocket-node/blob/main/docs/GRANT_COMPLETION_REPORT.md"
              external
            >
              Grant Report
            </FooterLink>
            <FooterLink
              href="https://github.com/RaheemJnr/pocket-node/blob/main/SECURITY.md"
              external
            >
              Security
            </FooterLink>
            <FooterLink href="/privacy">Privacy</FooterLink>
          </FooterColumn>

          <FooterColumn title="Project">
            <FooterLink href="https://t.me/pocket_node" external>
              Telegram
            </FooterLink>
            <FooterLink href="https://x.com/PocketNodeCKB" external>
              X (Twitter)
            </FooterLink>
            <FooterLink href="https://github.com/RaheemJnr/pocket-node" external>
              GitHub
            </FooterLink>
            <FooterLink
              href="https://github.com/RaheemJnr/pocket-node/releases/latest"
              external
            >
              Releases
            </FooterLink>
            <FooterLink
              href="https://github.com/RaheemJnr/pocket-node/issues/new"
              external
            >
              Send Feedback
            </FooterLink>
            <FooterLink
              href="https://talk.nervos.org/t/dis-mobile-ready-ckb-light-client-pocket-node-for-android/9879"
              external
            >
              Nervos Forum
            </FooterLink>
            <FooterLink href="https://nervos.org" external>
              Nervos Network
            </FooterLink>
            <FooterLink href="https://pocket-node-learn-ckb.vercel.app/" external>
              Learn CKB
            </FooterLink>
          </FooterColumn>
        </div>

        <div className="mt-12 flex flex-col items-start justify-between gap-3 border-t border-green/20 pt-6 md:flex-row md:items-center">
          <p className="font-doto text-xs font-semibold uppercase tracking-wide text-white/60">
            © 2026 Pocket Node. Open source under MIT.
          </p>
          <p className="font-doto text-xs font-semibold uppercase tracking-wide text-green/80">
            Funded by a Nervos Community DAO grant
          </p>
        </div>
      </div>
    </footer>
  )
}

function FooterColumn({
  title,
  children,
}: {
  title: string
  children: React.ReactNode
}) {
  return (
    <div>
      <h4 className="mb-4 font-doto text-sm font-black uppercase tracking-wider text-green">
        {title}
      </h4>
      <ul className="flex flex-col gap-2">{children}</ul>
    </div>
  )
}

function FooterLink({
  href,
  external,
  children,
}: {
  href: string
  external?: boolean
  children: React.ReactNode
}) {
  return (
    <li>
      <Link
        href={href}
        {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
        className="font-doto text-sm font-semibold text-white/80 transition-colors hover:text-green"
      >
        {children}
      </Link>
    </li>
  )
}
