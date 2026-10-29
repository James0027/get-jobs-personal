'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useEffect, useState } from 'react'
import { BiEnvelope, BiBriefcase, BiSearch, BiTask, BiUserCircle, BiBrain, BiMoon, BiSun, BiX } from 'react-icons/bi'
import { useTheme } from 'next-themes'
import { API_BASE } from '@/lib/api'

export default function Sidebar() {
  const pathname = usePathname()
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)

  // 健康检查状态：up / degraded / down / unknown
  const [health, setHealth] = useState<'up' | 'degraded' | 'down' | 'unknown'>('unknown')
  const [checking, setChecking] = useState(false)

  useEffect(() => {
    setMounted(true)
  }, [])

  // 路由变化时自动收起抽屉
  useEffect(() => {
    setMobileOpen(false)
  }, [pathname])

  // 监听来自 PageHeader 的打开抽屉事件
  useEffect(() => {
    const handleToggle = (e: Event) => {
      const customEvent = e as CustomEvent<boolean>
      if (typeof customEvent.detail === 'boolean') {
        setMobileOpen(customEvent.detail)
      } else {
        setMobileOpen((prev) => !prev)
      }
    }
    window.addEventListener('toggle-mobile-sidebar', handleToggle)
    return () => {
      window.removeEventListener('toggle-mobile-sidebar', handleToggle)
    }
  }, [])

  useEffect(() => {
    let interval: NodeJS.Timeout | null = null

    const check = async () => {
      if (checking) return
      setChecking(true)
      const baseUrl = process.env.API_BASE_URL || `${API_BASE}`

      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), 3000)
      try {
        // 先尝试自定义健康接口
        let res = await fetch(`${baseUrl}/api/health`, { signal: controller.signal })
        if (res.status === 404) {
          // 回退到 Spring Boot Actuator
          res = await fetch(`${baseUrl}/actuator/health`, { signal: controller.signal })
        }
        if (!res.ok) throw new Error(`status ${res.status}`)
        const data = await res.json()
        const statusRaw = (data.status || data.state || '').toString().toUpperCase()
        if (statusRaw === 'UP' || statusRaw === 'HEALTHY') {
          setHealth('up')
        } else if (statusRaw === 'DEGRADED' || statusRaw === 'WARN') {
          setHealth('degraded')
        } else {
          setHealth('down')
        }
      } catch (e) {
        setHealth('unknown')
      } finally {
        clearTimeout(timeout)
        setChecking(false)
      }
    }

    // 首次检查 + 轮询
    check()
    interval = setInterval(check, 30000)
    return () => {
      if (interval) clearInterval(interval)
    }
  }, [])

  const envGroup = [
    { href: '/env-config', icon: BiEnvelope, label: '环境配置' },
    { href: '/ai-config', icon: BiBrain, label: 'AI配置' },
  ]

  const platformGroup = [
    { href: '/boss', icon: BiBriefcase, label: 'Boss直聘' },
    { href: '/liepin', icon: BiSearch, label: '猎聘' },
    { href: '/51job', icon: BiTask, label: '51job' },
    { href: '/zhilian', icon: BiUserCircle, label: '智联招聘' },
  ]

  return (
    <>
      {/* 窄屏遮罩：点击自动关闭 */}
      {mobileOpen && (
        <div
          onClick={() => setMobileOpen(false)}
          className="fixed inset-0 bg-black/50 z-40 md:hidden backdrop-blur-sm transition-opacity"
          aria-hidden="true"
        />
      )}

      {/* 侧边栏：移动端抽屉（-translate-x-full / translate-x-0），桌面常驻（md:translate-x-0 md:w-64） */}
      <aside
        className={`fixed left-0 top-0 h-full w-64 bg-card text-card-foreground border-r border-border z-50 flex flex-col transition-transform duration-300 ease-in-out md:translate-x-0 ${
          mobileOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        {/* 侧边栏头部 */}
        <div className="p-5 border-b border-border">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2.5">
              <span className="text-2xl leading-none">🍀</span>
              <div>
                <h1 className="text-base font-semibold tracking-tight text-foreground">Get Jobs</h1>
                <p className="text-muted-foreground text-xs">配置管理中心</p>
              </div>
            </div>
            {/* 移动端关闭按钮 */}
            <button
              type="button"
              onClick={() => setMobileOpen(false)}
              className="md:hidden p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted"
              aria-label="关闭侧边栏"
            >
              <BiX className="text-xl" />
            </button>
          </div>

          {/* 状态指示器（保留语义色：成功=绿、警告=橙、失败=红、未知=灰） */}
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <div
              className={`w-2 h-2 rounded-full ${
                health === 'up'
                  ? 'bg-emerald-500'
                  : health === 'degraded'
                  ? 'bg-amber-500'
                  : health === 'down'
                  ? 'bg-red-500'
                  : 'bg-zinc-400'
              }`}
            />
            <span>
              {health === 'up'
                ? '系统运行正常'
                : health === 'degraded'
                ? '服务降级'
                : health === 'down'
                ? '服务异常'
                : '未连接'}
            </span>
          </div>

          {/* 主题切换器 */}
          {mounted && (
            <button
              onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
              className="mt-3 w-full flex items-center justify-center gap-2 px-3 py-1.5 rounded-md border border-border bg-background hover:bg-muted text-foreground text-xs font-medium transition-colors"
            >
              {theme === 'dark' ? (
                <>
                  <BiSun className="text-sm" />
                  <span>切换到浅色</span>
                </>
              ) : (
                <>
                  <BiMoon className="text-sm" />
                  <span>切换到深色</span>
                </>
              )}
            </button>
          )}
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 p-3 space-y-4 overflow-y-auto">
          {/* 环境配置分组 */}
          <div>
            <div className="px-3 py-1 text-[11px] font-semibold text-muted-foreground uppercase tracking-wider">
              环境配置
            </div>
            <div className="mt-1 space-y-1">
              {envGroup.map((item) => {
                const Icon = item.icon
                const isActive = pathname === item.href
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={`
                      flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium transition-colors
                      ${isActive
                        ? 'bg-muted text-foreground font-semibold'
                        : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
                      }
                    `}
                  >
                    <Icon className="text-lg text-current" />
                    <span>{item.label}</span>
                  </Link>
                )
              })}
            </div>
          </div>

          {/* 平台配置分组 */}
          <div>
            <div className="px-3 py-1 text-[11px] font-semibold text-muted-foreground uppercase tracking-wider">
              平台配置
            </div>
            <div className="mt-1 space-y-1">
              {platformGroup.map((item) => {
                const Icon = item.icon
                const isActive = pathname === item.href
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={`
                      flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium transition-colors
                      ${isActive
                        ? 'bg-muted text-foreground font-semibold'
                        : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
                      }
                    `}
                  >
                    <Icon className="text-lg text-current" />
                    <span>{item.label}</span>
                  </Link>
                )
              })}
            </div>
          </div>
        </nav>

        {/* 底部信息 */}
        <div className="p-3 border-t border-border text-center">
          <p className="text-muted-foreground text-xs">v1.0.0</p>
        </div>
      </aside>
    </>
  )
}
