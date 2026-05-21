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

export const metadata: Metadata = {
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
