"use client"
import { ReactNode } from 'react'
import { BiMenu } from 'react-icons/bi'

export default function PageHeader({
  icon,
  title,
  subtitle,
  iconClass = 'text-foreground',
  accentBgClass = 'bg-muted',
  actions,
}: {
  icon: ReactNode
  title: string
  subtitle?: string
  iconClass?: string
  accentBgClass?: string
  actions?: ReactNode
}) {
  const handleOpenDrawer = () => {
    window.dispatchEvent(new CustomEvent('toggle-mobile-sidebar', { detail: true }))
  }

  return (
    <div className="w-full min-w-0 sticky top-0 z-40 bg-background/95 backdrop-blur border-b border-border mb-6">
      <div className="flex min-w-0 items-center gap-2 md:gap-3 py-4 px-2">
        {/* 窄屏汉堡按钮 */}
        <button
          type="button"
          onClick={handleOpenDrawer}
          className="md:hidden inline-flex items-center justify-center p-2 rounded-md border border-border text-foreground hover:bg-muted focus:outline-none"
          aria-label="打开菜单"
        >
          <BiMenu className="text-xl" />
        </button>

        <div className={`p-2.5 rounded-lg ${accentBgClass} flex items-center justify-center shrink-0`}>
          <span className={`${iconClass} text-xl flex items-center justify-center`}>{icon}</span>
        </div>

        <div className="flex-1 min-w-0">
          <h1 className="text-xl md:text-2xl font-bold tracking-tight text-foreground truncate">
            {title}
          </h1>
          {subtitle && (
            <p className="text-muted-foreground mt-0.5 text-xs md:text-sm truncate">
              {subtitle}
            </p>
          )}
        </div>

        {actions && (
          <div className="ml-auto flex min-w-0 shrink basis-full md:basis-auto flex-wrap items-center justify-end gap-2">
            {actions}
          </div>
        )}
      </div>
    </div>
  )
}