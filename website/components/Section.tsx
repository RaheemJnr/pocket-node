/**
 * Reusable section header used across the homepage. Eyebrow label in
 * brand color, big Doto title, optional description paragraph. Mirrors
 * ckba's section-header rhythm.
 */
export function SectionHeader({
  eyebrow,
  title,
  description,
  align = 'left',
}: {
  eyebrow?: string
  title: string
  description?: string
  align?: 'left' | 'center'
}) {
  const alignClass = align === 'center' ? 'text-center mx-auto' : 'text-left'
  return (
    <div className={`max-w-2xl ${alignClass}`}>
      {eyebrow && (
        <p className="mb-4 font-doto text-xs font-black uppercase tracking-widest text-green">
          {eyebrow}
        </p>
      )}
      <h2 className="font-doto text-3xl font-bold uppercase leading-tight tracking-tight text-green md:text-5xl">
        {title}
      </h2>
      {description && (
        <p className="mt-6 font-doto text-base font-semibold leading-relaxed text-white/80 md:text-lg">
          {description}
        </p>
      )}
    </div>
  )
}
