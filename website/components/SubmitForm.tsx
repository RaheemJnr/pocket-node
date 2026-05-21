'use client'

import { useState } from 'react'

/**
 * Reusable form shell for the /claim and /waitlist endpoints. Both
 * pages share the same lifecycle (idle → submitting → success | error)
 * so the form state and the success / error UI are factored here. The
 * specific fields and endpoint live in the per-page render.
 */
export type FieldDef = {
  name: string
  label: string
  type?: 'email' | 'text'
  placeholder?: string
  autoComplete?: string
  /** Optional regex pattern (as a string) to match the value against.
   *  Functions cannot cross the server/client boundary, so validators
   *  are expressed as serializable regex patterns instead. */
  validatePattern?: string
  /** Error message shown when validatePattern does not match. */
  validateMessage?: string
}

export function SubmitForm({
  endpoint,
  fields,
  submitLabel,
  successTitle,
  successBody,
  perks,
}: {
  endpoint: string
  fields: FieldDef[]
  submitLabel: string
  successTitle: string
  successBody: string
  perks: string[]
}) {
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(fields.map((f) => [f.name, ''])),
  )
  const [status, setStatus] = useState<'idle' | 'submitting' | 'success' | 'error'>('idle')
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)

    for (const field of fields) {
      const value = values[field.name].trim()
      if (!value) {
        setError(`${field.label} is required`)
        return
      }
      if (field.validatePattern) {
        const re = new RegExp(field.validatePattern)
        if (!re.test(value)) {
          setError(field.validateMessage || `${field.label} is invalid`)
          return
        }
      }
    }

    setStatus('submitting')
    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        // Apps Script and formsubmit.co both read JSON via the request
        // body without requiring an Origin header, so we deliberately
        // skip Content-Type to dodge a CORS preflight that would block
        // the request from the browser.
        body: JSON.stringify(values),
      })
      if (!response.ok) throw new Error(`Server responded ${response.status}`)
      const data = await response.json().catch(() => ({ success: true }))
      if (data.success === false) throw new Error(data.error || 'Submission failed')
      setStatus('success')
    } catch (e) {
      setStatus('error')
      setError(e instanceof Error ? e.message : 'Submission failed. Try again in a moment.')
    }
  }

  if (status === 'success') {
    return (
      <div className="border border-green/40 bg-surface p-8 md:p-10">
        <div className="mb-6 inline-flex h-12 w-12 items-center justify-center border border-green bg-green/10 text-green">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" className="h-6 w-6">
            <polyline points="20,6 9,17 4,12" />
          </svg>
        </div>
        <h2 className="mb-3 font-doto text-2xl font-bold uppercase leading-tight tracking-tight text-green md:text-3xl">
          {successTitle}
        </h2>
        <p className="mb-8 font-sans text-base leading-relaxed text-white/85">{successBody}</p>
        <ul className="flex flex-col gap-3 border-t border-dashed border-green/30 pt-6">
          {perks.map((perk) => (
            <li key={perk} className="flex items-center gap-3 font-doto text-sm font-semibold text-white/80">
              <span className="text-green">✓</span>
              {perk}
            </li>
          ))}
        </ul>
      </div>
    )
  }

  return (
    <form onSubmit={handleSubmit} className="border border-green/40 bg-surface p-8 md:p-10">
      <div className="flex flex-col gap-6">
        {fields.map((field) => (
          <label key={field.name} className="flex flex-col gap-2">
            <span className="font-doto text-xs font-black uppercase tracking-widest text-green">
              {field.label}
            </span>
            <input
              type={field.type ?? 'text'}
              name={field.name}
              autoComplete={field.autoComplete}
              placeholder={field.placeholder}
              value={values[field.name]}
              onChange={(e) =>
                setValues((prev) => ({ ...prev, [field.name]: e.target.value }))
              }
              className="border border-green/40 bg-bg px-4 py-3 font-sans text-base text-white placeholder-white/40 transition-colors focus:border-green focus:outline-none"
              disabled={status === 'submitting'}
            />
          </label>
        ))}

        {error && (
          <div className="border border-red/60 bg-red/10 p-3 font-sans text-sm text-white">
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={status === 'submitting'}
          className="group inline-flex items-center justify-center gap-3 rounded-md border border-green bg-green/5 px-6 py-4 font-doto text-sm font-black uppercase leading-none tracking-wider text-green transition-colors hover:bg-green-deep hover:text-green-glow disabled:cursor-not-allowed disabled:opacity-50"
        >
          {status === 'submitting' ? 'Submitting…' : submitLabel}
          {status !== 'submitting' && (
            <span className="transition-transform group-hover:translate-x-1">→</span>
          )}
        </button>
      </div>
    </form>
  )
}
