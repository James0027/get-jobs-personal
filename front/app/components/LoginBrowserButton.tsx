'use client'

import { useState } from 'react'
import { BiLogIn } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { apiUrl } from '@/lib/api'

type LoginPlatform = 'boss' | 'liepin' | '51job' | 'zhilian'

export default function LoginBrowserButton({ platform }: { platform: LoginPlatform }) {
  const [starting, setStarting] = useState(false)
  const [message, setMessage] = useState('')

  const start = async () => {
    setStarting(true)
    setMessage('')
    try {
      const response = await fetch(apiUrl(`/api/login-only/${platform}/start`), { method: 'POST' })
      const data = await response.json().catch(() => null) as { message?: string } | null
      if (!response.ok) throw new Error(data?.message || '启动登录浏览器失败')
      setMessage(data?.message || '登录浏览器已启动')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '启动登录浏览器失败')
    } finally {
      setStarting(false)
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button size="sm" variant="outline" onClick={start} disabled={starting}>
        <BiLogIn className="mr-1" />
        {starting ? '启动中...' : '打开登录浏览器'}
      </Button>
      {message && <span className="text-xs text-muted-foreground">{message}</span>}
    </div>
  )
}
