import type { Metadata, Viewport } from 'next'
import { Inter, Doto } from 'next/font/google'
import './globals.css'

// Inter for body copy: same family the previous landing used and the
// same one the app itself ships in its res/font/ assets. Loading via
// next/font handles font-display: swap and zero-FOIT.
const inter = Inter({
  subsets: ['latin'],
  variable: '--font-inter',
  display: 'swap',
})

// Doto for display headers: the pixel-variable display font used across
// the CKB ecosystem (ckba.build, etc). Variable font; we pull the full
// weight range so we can use 400 / 700 / 900 from the same file.
const doto = Doto({
  subsets: ['latin'],
  variable: '--font-doto',
  display: 'swap',
  weight: ['400', '500', '700', '800', '900'],
})

// metadataBase: resolves relative OG image URLs against the canonical
// production domain. Without this set, Next.js falls back to
// http://localhost:3000 at build time and Open Graph images break on
// every share. Override per-deploy via NEXT_PUBLIC_SITE_URL if the
// preview build needs a different host.
const siteUrl =
  process.env.NEXT_PUBLIC_SITE_URL ?? 'https://pocketnode.app'

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: 'Pocket Node — CKB Light Wallet for Android',
  description:
    'The first Android wallet that runs a full CKB light client on-device. Your keys. Your sync. No middleman.',
  icons: {
    icon: '/icon.png',
  },
  openGraph: {
    title: 'Pocket Node — Your phone is the node',
    description:
      'A native Android wallet that runs a CKB light client on your device. No remote indexer. No custodian.',
    type: 'website',
    images: ['/icon.png'],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Pocket Node — Your phone is the node',
    description:
      'A native Android wallet that runs a CKB light client on your device.',
    images: ['/icon.png'],
  },
}

export const viewport: Viewport = {
  themeColor: '#0A0A0A',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en" className={`${inter.variable} ${doto.variable}`}>
      <body>{children}</body>
    </html>
  )
}
