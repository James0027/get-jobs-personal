'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { BiEdit } from 'react-icons/bi'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { apiUrl } from '@/lib/api'

interface Connection {
  provider: string
  protocol: string
  baseUrl: string
  model: string
  enabled: boolean
  apiKeyConfigured: boolean
  executionVerified: boolean
  simulated: boolean
  lastTestMessage: string
}

function readJson(response: Response): Promise<unknown> {
  return response.json().catch(() => null)
}

export default function AiConnectionSummary() {
  const [connection, setConnection] = useState<Connection | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const response = await fetch(apiUrl('/api/ai/connections'))
        const data = await readJson(response)
        if (!cancelled && Array.isArray(data) && data.length > 0) {
          setConnection(data[0] as Connection)
        }
      } catch {
        if (!cancelled) setConnection(null)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [])

  const simulated = Boolean(
    connection && (connection.simulated || connection.provider === 'STUB' || connection.protocol === 'STUB')
  )
  const real = Boolean(
    connection
    && !simulated
    && connection.provider
    && connection.baseUrl
    && connection.model
    && connection.apiKeyConfigured
  )

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-lg">模型连接</CardTitle>
          <Link href="/env-config" className="inline-flex items-center gap-1 text-xs text-muted-foreground underline underline-offset-2 hover:text-foreground">
            <BiEdit />
            编辑连接
          </Link>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-1.5 text-xs">
          <span className={`rounded-full px-2 py-0.5 ${real ? 'bg-emerald-500/10 text-emerald-600' : 'bg-muted text-muted-foreground'}`}>
            {real ? '已配置' : '未配置真实连接'}
          </span>
          <span className={`rounded-full px-2 py-0.5 ${connection?.executionVerified && !simulated ? 'bg-emerald-500/10 text-emerald-600' : 'bg-amber-500/10 text-amber-600'}`}>
            {connection?.executionVerified && !simulated ? '已测试' : '未测试'}
          </span>
        </div>
        {real && connection ? (
          <div className="grid grid-cols-1 gap-2 text-xs md:grid-cols-2">
            <div><span className="text-muted-foreground">Provider：</span>{connection.provider}</div>
            <div><span className="text-muted-foreground">Protocol：</span>{connection.protocol}</div>
            <div className="truncate"><span className="text-muted-foreground">Base URL：</span>{connection.baseUrl}</div>
            <div><span className="text-muted-foreground">模型：</span>{connection.model}</div>
            <div><span className="text-muted-foreground">密钥：</span>{connection.apiKeyConfigured ? '已配置' : '未配置'}</div>
            {connection.lastTestMessage && <div className="text-muted-foreground">{connection.lastTestMessage}</div>}
          </div>
        ) : (
          <p className="text-xs text-muted-foreground">请先在环境配置中完成接口类型、Base URL、模型和 API 密钥。</p>
        )}
      </CardContent>
    </Card>
  )
}
