'use client'

import { useEffect, useState } from 'react'
import { BiKey, BiRefresh, BiSave, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { apiUrl } from '@/lib/api'

type Status = { kind: 'ok' | 'error'; text: string }

interface Preset {
  id: string
  displayName: string
  connectionKind: string
  provider: string
  protocol: string
  defaultBaseUrl: string
  simulated: boolean
}

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

interface TestResult {
  success: boolean
  configured: boolean
  verified: boolean
  simulated: boolean
  message: string
}

function readJson(response: Response): Promise<unknown> {
  return response.json().catch(() => null)
}

function realPreset(preset: Preset): boolean {
  return preset.connectionKind === 'MODEL_API'
    && preset.protocol !== 'MCP'
    && preset.provider !== 'STUB'
    && !preset.simulated
}

export default function AiConnectionCard() {
  const [presets, setPresets] = useState<Preset[]>([])
  const [connection, setConnection] = useState<Connection | null>(null)
  const [typeId, setTypeId] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [showApiKey, setShowApiKey] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [message, setMessage] = useState<Status | null>(null)

  const load = async () => {
    try {
      const [presetResponse, connectionResponse] = await Promise.all([
        fetch(apiUrl('/api/ai/provider-presets')),
        fetch(apiUrl('/api/ai/connections')),
      ])
      const presetData = await readJson(presetResponse)
      const connectionData = await readJson(connectionResponse)
      const loadedPresets = (Array.isArray(presetData) ? presetData as Preset[] : []).filter(realPreset)
      const active = Array.isArray(connectionData) && connectionData.length > 0
        ? connectionData[0] as Connection
        : null
      setPresets(loadedPresets)
      setConnection(active)
      const activeIsSimulated = Boolean(
        active && (active.simulated || active.provider === 'STUB' || active.protocol === 'STUB')
      )
      if (active && !activeIsSimulated) {
        const matching = loadedPresets.find(
          (preset) => preset.provider === active.provider && preset.protocol === active.protocol
        )
        setTypeId(matching?.id ?? '')
        setBaseUrl(active.baseUrl || matching?.defaultBaseUrl || '')
        setModel(active.model || '')
      } else {
        setTypeId('')
        setBaseUrl('')
        setModel('')
      }
      setApiKey('')
      setDirty(false)
    } catch {
      setMessage({ kind: 'error', text: '连接配置未加载' })
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const selectedPreset = presets.find((preset) => preset.id === typeId)
  const isSimulated = Boolean(
    connection && (connection.simulated || connection.provider === 'STUB' || connection.protocol === 'STUB')
  )
  const isRealConfigured = Boolean(
    connection
    && !isSimulated
    && connection.provider
    && connection.baseUrl
    && connection.model
    && connection.apiKeyConfigured
  )
  const isVerified = Boolean(connection?.executionVerified && !isSimulated)

  const chooseType = (id: string) => {
    const preset = presets.find((item) => item.id === id)
    setTypeId(id)
    setBaseUrl(preset?.defaultBaseUrl ?? '')
    setDirty(true)
    setMessage(null)
  }

  const save = async () => {
    if (!selectedPreset) {
      setMessage({ kind: 'error', text: '请选择接口类型' })
      return
    }
    if (!baseUrl.trim() || !model.trim()) {
      setMessage({ kind: 'error', text: '请填写 Base URL 和模型' })
      return
    }
    if (!connection?.apiKeyConfigured && !apiKey.trim()) {
      setMessage({ kind: 'error', text: '请填写 API 密钥' })
      return
    }

    setSaving(true)
    setMessage(null)
    try {
      const response = await fetch(apiUrl('/api/ai/connection'), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          provider: selectedPreset.provider,
          protocol: selectedPreset.protocol,
          baseUrl: baseUrl.trim(),
          model: model.trim(),
        }),
      })
      const data = await readJson(response)
      if (!response.ok) {
        throw new Error((data as { message?: string } | null)?.message || '保存连接失败')
      }

      if (apiKey.trim()) {
        const keyResponse = await fetch(apiUrl('/api/config'), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ API_KEY: apiKey.trim() }),
        })
        if (!keyResponse.ok) {
          await load()
          setMessage({ kind: 'error', text: '连接已保存，但密钥保存失败' })
          return
        }
      }

      await load()
      setMessage({ kind: 'ok', text: '连接已保存' })
    } catch (error) {
      setMessage({ kind: 'error', text: error instanceof Error ? error.message : '保存连接失败' })
    } finally {
      setSaving(false)
    }
  }

  const test = async () => {
    if (!connection || isSimulated) {
      setMessage({ kind: 'error', text: '未配置真实连接' })
      return
    }
    if (dirty) {
      setMessage({ kind: 'error', text: '请先保存连接再测试' })
      return
    }
    if (!connection.apiKeyConfigured) {
      setMessage({ kind: 'error', text: '请先填写并保存 API 密钥' })
      return
    }
    if (!connection.enabled || !connection.baseUrl || !connection.model) {
      setMessage({ kind: 'error', text: '连接未配置完整' })
      return
    }

    setTesting(true)
    setMessage(null)
    try {
      const response = await fetch(apiUrl('/api/ai/connection/test'), { method: 'POST' })
      const data = await readJson(response)
      if (!response.ok) {
        throw new Error((data as { message?: string } | null)?.message || '连接测试失败')
      }
      const result = data as TestResult
      if (!result.success || result.simulated || !result.verified) {
        setConnection((prev) => prev ? { ...prev, executionVerified: false } : prev)
        setMessage({ kind: 'error', text: result.message || '连接测试失败' })
        return
      }
      setConnection((prev) => prev ? { ...prev, executionVerified: true, lastTestMessage: result.message } : prev)
      setMessage({ kind: 'ok', text: '连接测试成功' })
    } catch (error) {
      setConnection((prev) => prev ? { ...prev, executionVerified: false } : prev)
      setMessage({ kind: 'error', text: error instanceof Error ? error.message : '连接测试失败' })
    } finally {
      setTesting(false)
    }
  }

  const clearKey = async () => {
    if (!confirm('确认清除服务端保存的 API 密钥？')) return
    try {
      const response = await fetch(apiUrl('/api/config/API_KEY'), { method: 'DELETE' })
      if (!response.ok) throw new Error('清除密钥失败')
      setConnection((prev) => prev ? {
        ...prev,
        apiKeyConfigured: false,
        executionVerified: false,
        enabled: false,
      } : prev)
      setApiKey('')
      setMessage({ kind: 'ok', text: '密钥已清除' })
    } catch (error) {
      setMessage({ kind: 'error', text: error instanceof Error ? error.message : '清除密钥失败' })
    }
  }

  return (
    <Card className="bg-card border-border text-foreground shadow-none rounded-lg">
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-base flex items-center gap-2">
            <BiKey className="text-muted-foreground" />
            模型连接
          </CardTitle>
          <div className="flex flex-wrap items-center gap-1.5 text-xs">
            <span className={`rounded-full px-2 py-0.5 ${isRealConfigured ? 'bg-emerald-500/10 text-emerald-600' : 'bg-muted text-muted-foreground'}`}>
              {isRealConfigured ? '已配置' : '未配置'}
            </span>
            <span className={`rounded-full px-2 py-0.5 ${isVerified ? 'bg-emerald-500/10 text-emerald-600' : 'bg-amber-500/10 text-amber-600'}`}>
              {isVerified ? '已测试' : '未测试'}
            </span>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <Label>接口类型</Label>
            <Select value={typeId} onChange={(event) => chooseType(event.target.value)} placeholder="选择接口类型">
              {presets.map((preset) => (
                <option key={preset.id} value={preset.id}>{preset.displayName}</option>
              ))}
            </Select>
          </div>
          <div>
            <Label htmlFor="connection-model">模型</Label>
            <Input
              id="connection-model"
              className="mt-1"
              value={model}
              onChange={(event) => {
                setModel(event.target.value)
                setDirty(true)
              }}
              placeholder="手动填写模型 ID"
            />
          </div>
        </div>

        <div>
          <Label htmlFor="connection-base-url">Base URL</Label>
          <Input
            id="connection-base-url"
            className="mt-1"
            value={baseUrl}
            onChange={(event) => {
              setBaseUrl(event.target.value)
              setDirty(true)
            }}
            placeholder="https://..."
          />
        </div>

        <div>
          <Label htmlFor="connection-api-key">API 密钥</Label>
          <div className="mt-1 flex gap-2">
            <Input
              id="connection-api-key"
              type={showApiKey ? 'text' : 'password'}
              value={apiKey}
              onChange={(event) => {
                setApiKey(event.target.value)
                setDirty(true)
              }}
              placeholder={connection?.apiKeyConfigured ? '留空保留已有密钥' : '请输入 API 密钥'}
            />
            <Button type="button" variant="outline" size="sm" onClick={() => setShowApiKey((value) => !value)}>
              {showApiKey ? '隐藏' : '显示'}
            </Button>
            {connection?.apiKeyConfigured && (
              <Button type="button" variant="outline" size="sm" onClick={clearKey} className="text-destructive">
                清除
              </Button>
            )}
          </div>
        </div>

        {connection && !isSimulated && !isRealConfigured && (
          <p className="text-xs text-amber-600">连接未配置完整</p>
        )}
        {isSimulated && (
          <p className="text-xs text-amber-600">未配置真实连接</p>
        )}

        <div className="flex flex-wrap gap-2">
          <Button size="sm" onClick={save} disabled={saving}>
            <BiSave />
            <span className="ml-1">{saving ? '保存中...' : '保存连接'}</span>
          </Button>
          <Button size="sm" variant="outline" onClick={test} disabled={testing}>
            <BiRefresh />
            <span className="ml-1">{testing ? '测试中...' : '测试连接'}</span>
          </Button>
        </div>

        {message && (
          <div className={`flex items-center justify-between gap-2 rounded-lg border p-2.5 text-xs ${
            message.kind === 'error'
              ? 'border-destructive/30 bg-destructive/10 text-destructive'
              : 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
          }`}>
            <span>{message.text}</span>
            <button type="button" onClick={() => setMessage(null)} aria-label="关闭提示"><BiX /></button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
