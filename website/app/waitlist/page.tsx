import { Navbar } from '@/components/Navbar'
import { Footer } from '@/components/Footer'
import { SubmitForm } from '@/components/SubmitForm'

export const metadata = {
  title: 'Join the Waitlist — Pocket Node',
  description:
    'Get early access to Pocket Node closed testing on Google Play.',
}

// formsubmit.co handles inbox forwarding for the early-access list.
// First submission triggers a confirmation email; subsequent ones are
// delivered to the maintainer mailbox. Endpoint shape preserved from
// the legacy waitlist.html.
const FORM_ENDPOINT = 'https://formsubmit.co/ajax/raheemjnr@gmail.com'

export default function WaitlistPage() {
  return (
    <>
      <Navbar />

      <main className="border-b border-green/20 bg-bg">
        <div className="mx-auto grid max-w-page grid-cols-1 gap-12 px-6 py-16 md:grid-cols-[1fr_minmax(0,420px)] md:gap-16 md:px-12 md:py-24">
          <div>
            <p className="mb-4 font-doto text-xs font-black uppercase tracking-widest text-green">
              Early access
            </p>
            <h1 className="mb-6 font-doto text-4xl font-bold uppercase leading-tight tracking-tight text-green md:text-5xl">
              Get early access to Pocket Node
            </h1>
            <p className="mb-6 max-w-md font-sans text-base leading-relaxed text-white/85">
              The Play Store listing is in preparation. We&#39;re opening the
              app to a small group of testers first. Join the waitlist and be
              among the first to run a real CKB light client on your Android
              phone.
            </p>
            <ul className="flex flex-col gap-3 font-doto text-sm font-semibold text-white/80">
              <li className="flex items-center gap-3">
                <span className="text-green">✓</span> Closed testing on Google Play
              </li>
              <li className="flex items-center gap-3">
                <span className="text-green">✓</span> No spam, no newsletters
              </li>
              <li className="flex items-center gap-3">
                <span className="text-green">✓</span> One email when the invite is ready
              </li>
              <li className="flex items-center gap-3">
                <span className="text-green">✓</span> Sideload from GitHub today if you can&#39;t wait
              </li>
            </ul>
          </div>

          <SubmitForm
            endpoint={FORM_ENDPOINT}
            fields={[
              {
                name: 'email',
                label: 'Email',
                type: 'email',
                placeholder: 'you@example.com',
                autoComplete: 'email',
              },
            ]}
            submitLabel="Join the waitlist"
            successTitle="You're on the list"
            successBody="We'll send you a Google Play closed testing invite when the next batch opens. Keep an eye on your inbox."
            perks={[
              'Email saved to the closed-testing list',
              'Confirmation email if this is your first sign-up',
              'Closed testing invite when the next batch opens',
              'No marketing, no newsletters',
            ]}
          />
        </div>
      </main>

      <Footer />
    </>
  )
}
