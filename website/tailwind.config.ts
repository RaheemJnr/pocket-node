import type { Config } from 'tailwindcss'

const config: Config = {
  content: [
    './app/**/*.{js,ts,jsx,tsx,mdx}',
    './components/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        // Pocket Node brand palette, mirroring app/src/main/java/com/rjnr/
        // pocketnode/ui/theme/Theme.kt. Structure modeled on ckba.build
        // (sharp borders, all-caps display font) but recolored to the app
        // green instead of ckba purple.
        bg: '#0A0A0A',
        surface: '#101010',
        green: {
          DEFAULT: '#1DD781', // PocketGreen — primary accent
          light: '#A4F5C9',   // secondary text, subtitles
          glow: '#C8F8DC',    // hover-light
          deep: '#0F4A2E',    // hover-dim, pressed state
        },
        outline: '#1DD78133', // 30% green for sharp borders
        amber: '#F59E0B',
        red: '#FF4444',
      },
      fontFamily: {
        // Doto is the display font matching ckba's pixel-variable look.
        // Inter remains for body text where readability matters more
        // than aesthetic.
        doto: ['var(--font-doto)', 'monospace'],
        sans: ['var(--font-inter)', 'system-ui', 'sans-serif'],
      },
      maxWidth: {
        page: '1440px',
        content: '720px',
      },
    },
  },
  plugins: [],
}

export default config
