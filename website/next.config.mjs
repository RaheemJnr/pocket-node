/** @type {import('next').NextConfig} */
const nextConfig = {
  // Preserve the X-* security headers from the legacy vercel.json. Routing
  // for /privacy /claim /waitlist is now native (App Router pages) so the
  // rewrites that pointed at *.html files are no longer needed.
  async headers() {
    return [
      {
        source: '/(.*)',
        headers: [
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'X-Frame-Options', value: 'DENY' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
        ],
      },
    ]
  },
}

export default nextConfig
