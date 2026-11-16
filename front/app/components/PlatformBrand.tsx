import Image from 'next/image'
import { PLATFORM_META, type PlatformId } from '@/lib/platforms'

export default function PlatformBrand({
  platform,
  showLabel = true,
  size = 24,
}: {
  platform: PlatformId
  showLabel?: boolean
  size?: number
}) {
  const meta = PLATFORM_META[platform]
  return (
    <span className="inline-flex min-w-0 items-center gap-2">
      <span
        className="inline-flex shrink-0 items-center justify-center rounded-md border border-border bg-background"
        style={{ width: size + 8, height: size + 8 }}
      >
        <Image
          src={meta.icon}
          alt={meta.label}
          width={size}
          height={size}
          unoptimized
          className="object-contain"
        />
      </span>
      {showLabel && <span className="truncate text-sm font-semibold text-foreground">{meta.label}</span>}
    </span>
  )
}
