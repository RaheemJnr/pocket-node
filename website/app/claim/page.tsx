import { Navbar } from '@/components/Navbar'
import { Footer } from '@/components/Footer'
import { SubmitForm } from '@/components/SubmitForm'

export const metadata = {
  title: 'Claim Your CKB — Pocket Node',
  description:
    'Lagos CKB Meetup attendees: claim 1,000 CKB to your Pocket Node wallet.',
}

// Apps Script Web App endpoint that records claims into the meetup
// spreadsheet. Documented in website/public/apps-script/claim-handler.js.
const CLAIM_ENDPOINT =
  'https://script.google.com/macros/s/AKfycbx5DD8WJZl5a7rNC2OqermZ2iZ6JJ6w7atZXmVtYm7zXCDAXPxDuYkHiH_Mda-m7WKv0Q/exec'

// Bech32 character set used by CKB mainnet addresses. The leading
// "ckb1" prefix is mainnet-only; testnet addresses (ckt1...) are
// rejected here because the claim is funded from mainnet supply.
// Stored as a string because field validators cross the server →
// client boundary and functions cannot.
const CKB_MAINNET_PATTERN = '^ckb1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{42,}$'

export default function ClaimPage() {
  return (
    <>
      <Navbar />

      <main className="border-b border-green/20 bg-bg">
        <div className="mx-auto grid max-w-page grid-cols-1 gap-12 px-6 py-16 md:grid-cols-[1fr_minmax(0,420px)] md:gap-16 md:px-12 md:py-24">
          <div>
            <p className="mb-4 font-doto text-xs font-black uppercase tracking-widest text-green">
              Lagos CKB Meetup
            </p>
            <h1 className="mb-6 font-doto text-4xl font-bold uppercase leading-tight tracking-tight text-green md:text-5xl">
              Claim your free CKB
            </h1>
            <p className="mb-6 max-w-md font-sans text-base leading-relaxed text-white/85">
              You&#39;re registered for the Lagos CKB Meetup. Enter the email
              you used to register and your Pocket Node wallet address to
              claim 1,000 CKB. Your tokens will be sent after the event.
            </p>
            <ul className="flex flex-col gap-3 font-doto text-sm font-semibold text-white/80">
              <li className="flex items-center gap-3">
                <span className="text-green">✓</span> One claim per registered email
              </li>
              <li className="flex items-center gap-3">
                <span className="text-green">✓</span> Mainnet CKB, paid to the address you provide
              </li>
              <li className="flex items-center gap-3">
                <span className="text-green">✓</span> No fee, no signup
              </li>
            </ul>
          </div>

          <SubmitForm
            endpoint={CLAIM_ENDPOINT}
            fields={[
              {
                name: 'email',
                label: 'Email',
                type: 'email',
                placeholder: 'you@example.com',
                autoComplete: 'email',
              },
              {
                name: 'address',
                label: 'CKB Address',
                type: 'text',
                placeholder: 'ckb1q...',
                validatePattern: CKB_MAINNET_PATTERN,
                validateMessage:
                  'Enter a valid CKB mainnet address (starts with ckb1).',
              },
            ]}
            submitLabel="Claim 1,000 CKB"
            successTitle="Claim received"
            successBody="Your CKB address has been saved. 1,000 CKB will be sent to your wallet after the event."
            perks={[
              'Wallet address recorded',
              'Pre-event email confirmation',
              '1,000 CKB sent to your address post-event',
              'No further action required',
            ]}
          />
        </div>
      </main>

      <Footer />
    </>
  )
}
