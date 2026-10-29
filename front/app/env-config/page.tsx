'use client'

import { useState, useEffect } from 'react'
import { BiSave, BiKey, BiLinkExternal, BiCodeAlt, BiInfoCircle } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'

export default function EnvConfig() {
  const [envConfig, setEnvConfig] = useState({
    hookUrl: '',
    baseUrl: '',
    apiKey: '',
    model: '',
    botIsSend: 0,
  })

  const [showApiKey, setShowApiKey] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)

  // 从数据库加载配置
  const fetchConfig = async () => {
    try {
      setLoading(true)
      const response = await fetch(`${API_BASE}/api/config`, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
        },
      })

      if (!response.ok) {
        throw new Error('获取配置失败')
      }

      const result = await response.json()

      if (result.success && result.data) {
        setEnvConfig({
          hookUrl: result.data.HOOK_URL || '',
          baseUrl: result.data.BASE_URL || '',
          apiKey: result.data.API_KEY || '',
          model: result.data.MODEL || '',
          botIsSend: (() => {
            const raw = result.data.BOT_IS_SEND
            const val = String(raw ?? '').trim().toLowerCase()
            return val === '1' || val === 'true' ? 1 : 0
          })(),
        })
      }
    } catch (error) {
      console.error('获取配置失败:', error)
      alert('获取配置失败，请检查后端服务是否正常运行')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchConfig()
  }, [])

  const handleSave = async (silent: boolean = false) => {
    try {
      setSaving(true)

      const configMap = {
        HOOK_URL: envConfig.hookUrl,
        BASE_URL: envConfig.baseUrl,
        API_KEY: envConfig.apiKey,
        MODEL: envConfig.model,
        BOT_IS_SEND: String(envConfig.botIsSend ?? 0),
      }

      const response = await fetch(`${API_BASE}/api/config`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(configMap),
      })

      if (!response.ok) {
        throw new Error('保存配置失败')
      }

      const result = await response.json()

      if (result.success) {
        if (!silent) {
          setSaveResult({ success: true, message: '保存成功' })
          setShowSaveDialog(true)
        }
      } else {
        throw new Error(result.message || '保存配置失败')
      }
    } catch (error) {
      console.error('保存配置失败:', error)
      if (!silent) {
        setSaveResult({ success: false, message: '保存配置失败：网络或服务异常。' })
        setShowSaveDialog(true)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiCodeAlt className="text-xl" />}
        title="环境变量配置"
        subtitle=".env_template 环境变量管理"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button
              onClick={() => handleSave(false)}
              size="sm"
              disabled={saving}
              className="bg-primary text-primary-foreground hover:bg-primary/90"
            >
              <BiSave className="mr-1" /> 保存配置
            </Button>
          </div>
        }
      />

      {loading && (
        <Card className="bg-card border-border text-foreground shadow-none rounded-lg">
          <CardContent className="pt-6">
            <p className="text-center text-sm text-muted-foreground">加载配置中...</p>
          </CardContent>
        </Card>
      )}

      <div className="space-y-6">
        {/* 企业微信 Webhook */}
        <Card className="bg-card border-border text-foreground shadow-none rounded-lg">
          <CardHeader className="flex flex-row items-start justify-between gap-4">
            <div className="min-w-0 space-y-1">
              <CardTitle className="flex items-center gap-2">
                <BiLinkExternal className="text-muted-foreground" />
                企业微信 Webhook
              </CardTitle>
              <CardDescription>配置企业微信群机器人，用于接收通知消息</CardDescription>
            </div>
            <div>
              <button
                type="button"
                id="botIsSend"
                aria-label="企业微信发送开关"
                onClick={() => setEnvConfig({ ...envConfig, botIsSend: envConfig.botIsSend ? 0 : 1 })}
                className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 border border-input ${envConfig.botIsSend ? 'bg-primary' : 'bg-input'}`}
              >
                <span
                  className={`pointer-events-none inline-block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${envConfig.botIsSend ? 'translate-x-5' : 'translate-x-0'}`}
                />
              </button>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              <Label htmlFor="hookUrl">Webhook URL</Label>
              <Input
                id="hookUrl"
                type="text"
                value={envConfig.hookUrl}
                onChange={(e) => setEnvConfig({ ...envConfig, hookUrl: e.target.value })}
                placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key"
              />
              <p className="text-xs text-muted-foreground">
                企业微信群机器人webhook地址，用于接收通知消息
              </p>
            </div>
          </CardContent>
        </Card>

        {/* API 配置 */}
        <Card className="bg-card border-border text-foreground shadow-none rounded-lg">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiCodeAlt className="text-muted-foreground" />
              API 配置
            </CardTitle>
            <CardDescription>配置 API 服务器地址和使用的 AI 模型</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="baseUrl">API Base URL</Label>
                <Input
                  id="baseUrl"
                  type="text"
                  value={envConfig.baseUrl}
                  onChange={(e) => setEnvConfig({ ...envConfig, baseUrl: e.target.value })}
                  placeholder="https://api.ruyun.fun"
                />
                <p className="text-xs text-muted-foreground">API服务器地址</p>
              </div>

              <div className="space-y-2">
                <Label htmlFor="model">AI模型</Label>
                <Input
                  id="model"
                  type="text"
                  value={envConfig.model}
                  onChange={(e) => setEnvConfig({ ...envConfig, model: e.target.value })}
                  placeholder="gpt-5-nano-2025-08-07"
                />
                <p className="text-xs text-muted-foreground">使用的AI模型名称</p>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* API 密钥 */}
        <Card className="bg-card border-border text-foreground shadow-none rounded-lg">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiKey className="text-muted-foreground" />
              API 密钥
            </CardTitle>
            <CardDescription>配置 API 访问密钥，请妥善保管</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              <Label htmlFor="apiKey">API Key</Label>
              <div className="flex gap-2">
                <Input
                  id="apiKey"
                  type={showApiKey ? 'text' : 'password'}
                  value={envConfig.apiKey}
                  onChange={(e) => setEnvConfig({ ...envConfig, apiKey: e.target.value })}
                  placeholder="sk-xxxxxxxxxxxxxxxxx"
                  className="flex-1 min-w-0"
                />
                <Button
                  onClick={() => setShowApiKey(!showApiKey)}
                  variant="outline"
                  size="sm"
                  type="button"
                  className="shrink-0 px-3"
                >
                  {showApiKey ? '隐藏' : '显示'}
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                🔐 API密钥将被安全存储，请妥善保管
              </p>
            </div>
          </CardContent>
        </Card>

        {/* 安全提示 */}
        <Card className="bg-card border-border text-foreground shadow-none rounded-lg">
          <CardContent className="pt-6">
            <div className="flex gap-3">
              <BiInfoCircle className="h-5 w-5 text-muted-foreground flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm text-foreground">
                  <strong className="font-semibold">提示：</strong> 这些环境变量将保存到{' '}
                  <code className="bg-muted px-1.5 py-0.5 rounded text-foreground font-mono text-xs">.env</code>{' '}
                  文件中。请勿将包含敏感信息的 .env 文件提交到版本控制系统。
                </p>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 保存结果弹框 */}
        {showSaveDialog && saveResult && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" role="dialog" aria-modal="true">
            <Card className="bg-card border-border text-foreground rounded-lg shadow-none w-[92%] max-w-sm">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiSave className={saveResult.success ? 'text-emerald-500' : 'text-destructive'} />
                  {saveResult.success ? '保存成功' : '保存失败'}
                </CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowSaveDialog(false)}
                    size="sm"
                    className={saveResult.success ? 'bg-emerald-600 hover:bg-emerald-700 text-white' : 'bg-destructive hover:bg-destructive/90 text-destructive-foreground'}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        )}
      </div>
    </div>
  )
}
