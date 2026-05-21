import Link from 'next/link'
import { Navbar } from '@/components/Navbar'
import { Hero } from '@/components/Hero'
import { FeatureCard } from '@/components/FeatureCard'
import { SectionHeader } from '@/components/Section'
import { Footer } from '@/components/Footer'

/**
 * Homepage. Vertical flow modeled on ckba.build:
 *   Hero → Features → Sovereignty (security) → How → Closing CTA → Footer.
 * Sharp 1px borders, no rounded corners except buttons, all-caps Doto
 * headers, Inter for body, brand-green accent on PocketGreen #1DD781.
 */
export default function HomePage() {
  return (
    <>
      <Navbar />
      <Hero />

      <Features />
      <Sovereignty />
      <HowItWorks />
      <ClosingCta />

      <Footer />
    </>
  )
}

// region: Features --------------------------------------------------------

function Features() {
  return (
    <section id="features" className="border-b border-green/20 bg-bg py-24 md:py-32">
      <div className="mx-auto max-w-page px-6 md:px-12">
        <div className="mb-16">
          <SectionHeader
            eyebrow="Features"
            title="Everything you need. Nothing you don't."
            description="A complete CKB wallet that runs on your phone instead of someone else's server. Sovereignty without sacrificing the basics."
          />
        </div>

        <div className="grid grid-cols-1 gap-0 md:grid-cols-2 md:gap-px md:bg-green/20 lg:grid-cols-3">
          {features.map((feature) => (
            <FeatureCard key={feature.title} {...feature} />
          ))}
        </div>
      </div>
    </section>
  )
}

const features = [
  {
    title: 'Embedded Light Client',
    description:
      "A Rust CKB light client runs natively in-process via JNI. Balance, history, and transaction broadcast are all computed locally on your phone. No remote indexer, ever.",
    icon: <IconBolt />,
  },
  {
    title: 'Multi-Wallet & HD Sub-Accounts',
    description:
      'Unlimited wallets per install. Sub-accounts derive from a single parent recovery phrase under BIP44, so one backup covers all of them.',
    icon: <IconUsers />,
  },
  {
    title: 'Address Book',
    description:
      "Contacts with smart suggestions and Send-screen autocomplete. After every successful send, the app offers to save the recipient if it isn't already saved.",
    icon: <IconBook />,
  },
  {
    title: 'Nervos DAO',
    description:
      'Native deposit, two-phase withdrawal (initiate + complete after the protocol lock period), per-cell compensation tracking from on-chain header DAO fields.',
    icon: <IconShield />,
  },
  {
    title: 'Hardware-Backed Keys',
    description:
      'AES-256-GCM key bound to user authentication via the Android Keystore. Hardware-backed via TEE or Secure Element on Android 9 and newer.',
    icon: <IconLock />,
  },
  {
    title: 'Argon2id PIN',
    description:
      'PIN derivation with 64 MB memory cost, t=3, p=1. Cumulative 24-hour-decay lockout; permanent lockout at 10+ failures. Offline brute-force is impractical.',
    icon: <IconKey />,
  },
  {
    title: 'Four Sync Modes',
    description:
      'New wallet (instant), Recent (about 2 minutes), Custom block height with explorer-deeplink helper, Full history (overnight). Pick the right depth for your use.',
    icon: <IconRefresh />,
  },
  {
    title: 'Sync Stall Detector',
    description:
      'If the light client stops advancing for 5 minutes while away from tip, the home screen shows a one-tap Use Recent recovery banner. No silent stalls.',
    icon: <IconAlert />,
  },
  {
    title: 'In-App Updates',
    description:
      'New releases install in one tap. A Telegram-style banner shows download progress and the install CTA across tab switches. No manual sideload after the first install.',
    icon: <IconDownload />,
  },
]

// region: Sovereignty -----------------------------------------------------

function Sovereignty() {
  return (
    <section id="security" className="relative border-b border-green/20 bg-surface py-24 md:py-32">
      <div className="mx-auto max-w-page px-6 md:px-12">
        <div className="grid grid-cols-1 gap-12 md:grid-cols-2 md:items-center md:gap-16">
          <div>
            <SectionHeader
              eyebrow="Sovereignty"
              title="No servers. No trust. No surprises."
              description="Most mobile CKB wallets, including ones labelled non-custodial, depend on a remote indexer to know your balance. Pocket Node does not. The light client embedded in the app verifies every header and every cell against the network directly."
            />
            <div className="mt-10 flex flex-col gap-3 font-doto text-sm font-semibold uppercase tracking-wide text-white/80">
              <SovereigntyPoint>Keys never leave the device</SovereigntyPoint>
              <SovereigntyPoint>No analytics, no telemetry</SovereigntyPoint>
              <SovereigntyPoint>No phone-home, no remote API</SovereigntyPoint>
              <SovereigntyPoint>Reproducible from source</SovereigntyPoint>
              <SovereigntyPoint>Internal Phase 1 audits complete</SovereigntyPoint>
            </div>
          </div>

          <div className="relative border border-green/40 bg-bg p-8 md:p-10">
            <div className="dot-grid-bg absolute inset-x-0 top-0 h-8 opacity-30" aria-hidden />
            <p className="mb-4 font-doto text-xs font-black uppercase tracking-widest text-green">
              Threat model
            </p>
            <ul className="flex flex-col gap-4 font-doto text-sm font-semibold leading-relaxed text-white/90">
              <li>
                <span className="text-green">Defended against:</span> lost or stolen device with locked screen, malicious apps on the same device, screen capture by other apps, clipboard exposure of keys.
              </li>
              <li>
                <span className="text-green">Not defended against:</span> a leaked recovery phrase, phishing on the recipient address, a sophisticated physical attacker with full access to your unlocked phone.
              </li>
            </ul>
            <Link
              href="/guide#security-model"
              className="arrow mt-8 inline-flex font-doto text-sm font-black uppercase tracking-wider text-green transition-colors hover:text-green-glow"
            >
              Read the security model
            </Link>
          </div>
        </div>
      </div>
    </section>
  )
}

function SovereigntyPoint({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3">
      <span className="block h-2 w-2 bg-green" aria-hidden />
      {children}
    </div>
  )
}

// region: How it works ----------------------------------------------------

function HowItWorks() {
  return (
    <section className="border-b border-green/20 bg-bg py-24 md:py-32">
      <div className="mx-auto max-w-page px-6 md:px-12">
        <div className="mb-16 text-center">
          <SectionHeader
            eyebrow="How it works"
            title="Up and running in minutes"
            description="No accounts to create, no email to verify, no KYC. Install, generate a wallet, sync, transact."
            align="center"
          />
        </div>

        <div className="grid grid-cols-1 gap-px bg-green/20 md:grid-cols-4">
          <Step number="01" title="Install" body="Download Pocket Node from the latest GitHub Release and install the APK." />
          <Step number="02" title="Create" body="Generate a 12-word seed phrase. Set up a PIN and (optionally) biometric unlock." />
          <Step number="03" title="Sync" body="Pick a sync mode. The embedded light client connects to CKB peers and starts scanning." />
          <Step number="04" title="Transact" body="Send, receive, deposit into Nervos DAO. All signing happens on-device." />
        </div>
      </div>
    </section>
  )
}

function Step({ number, title, body }: { number: string; title: string; body: string }) {
  return (
    <div className="flex flex-col gap-4 bg-surface p-8 md:p-10">
      <span className="font-doto text-2xl font-black text-green md:text-3xl">{number}</span>
      <h3 className="font-doto text-xl font-bold uppercase leading-tight tracking-tight text-white md:text-2xl">
        {title}
      </h3>
      <p className="font-doto text-sm font-semibold leading-relaxed text-white/80">{body}</p>
    </div>
  )
}

// region: Closing CTA -----------------------------------------------------

function ClosingCta() {
  return (
    <section className="relative overflow-hidden border-b border-green/20 bg-bg py-24 md:py-32">
      <div className="dot-grid-bg absolute inset-0 opacity-30" aria-hidden />
      <div className="relative mx-auto flex max-w-page flex-col items-center px-6 text-center md:px-12">
        <p className="mb-6 font-doto text-xs font-black uppercase tracking-widest text-green">
          Take control
        </p>
        <h2 className="mb-6 font-doto text-3xl font-bold uppercase leading-tight tracking-tight text-green md:text-6xl">
          Your phone. The node.
        </h2>
        <p className="mb-12 max-w-xl font-doto text-base font-semibold leading-relaxed text-white/80 md:text-lg">
          The Play Store listing is in preparation. Until then, install the latest release directly from GitHub.
        </p>
        <div className="flex flex-col items-stretch gap-3 sm:flex-row sm:items-center">
          <Link
            href="/download"
            className="group inline-flex items-center justify-center gap-3 rounded-md border border-green bg-green/5 px-8 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green transition-colors hover:bg-green-deep hover:text-green-glow"
          >
            Download for Android
            <span className="transition-transform group-hover:translate-x-1">→</span>
          </Link>
          <Link
            href="https://github.com/RaheemJnr/pocket-node"
            target="_blank"
            rel="noopener noreferrer"
            className="group inline-flex items-center justify-center gap-3 rounded-md border border-green/40 px-8 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green/80 transition-colors hover:border-green hover:text-green"
          >
            Star on GitHub
            <span className="transition-transform group-hover:translate-x-1">→</span>
          </Link>
        </div>
      </div>
    </section>
  )
}

// region: Inline icons (currentColor SVG strokes) -------------------------

function IconBolt() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <polyline points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
    </svg>
  )
}
function IconUsers() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  )
}
function IconBook() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
      <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
    </svg>
  )
}
function IconShield() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
      <path d="M9 12l2 2 4-4" />
    </svg>
  )
}
function IconLock() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <rect x="3" y="11" width="18" height="11" rx="0" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  )
}
function IconKey() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <circle cx="7.5" cy="15.5" r="3.5" />
      <path d="M10 13l11-11" />
      <path d="M16 8l3 3" />
    </svg>
  )
}
function IconRefresh() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <polyline points="23 4 23 10 17 10" />
      <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
    </svg>
  )
}
function IconAlert() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  )
}
function IconDownload() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="7 10 12 15 17 10" />
      <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
  )
}
