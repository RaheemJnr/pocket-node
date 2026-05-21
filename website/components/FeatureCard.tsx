/**
 * Sharp-bordered card matching ckba's membership-section card pattern:
 * 1px solid border in the brand color, dashed divider between the header
 * and body, no rounded corners (deliberate brutalist feel), Doto headers.
 */
export function FeatureCard({
  title,
  description,
  icon,
}: {
  title: string
  description: string
  icon: React.ReactNode
}) {
  return (
    <div className="group flex flex-col gap-4 border border-green/40 bg-surface p-6 transition-colors hover:border-green md:p-8">
      <div className="flex items-start gap-4">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center border border-green/60 text-green">
          {icon}
        </div>
        <h3 className="pt-1 font-doto text-lg font-bold uppercase leading-tight tracking-tight text-green md:text-xl">
          {title}
        </h3>
      </div>
      <div className="border-t border-dashed border-green/40" />
      <p className="font-doto text-sm font-semibold leading-relaxed text-white/90 md:text-[15px]">
        {description}
      </p>
    </div>
  )
}
