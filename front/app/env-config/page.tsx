'use client'

import { useState, useEffect } from 'react'
import { BiSave, BiLinkExternal, BiCodeAlt } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import PageHeader from '@/app/components/PageHeader'
import AiConnectionCard from '@/app/components/AiConnectionCard'
import { API_BASE } from '@/lib/api'

export default function EnvConfig() {
  const [envConfig, setEnvConfig] = useState({
    hookUrl: '',
    botIsSend: 0,
  })

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)

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

      const configMap: Record<string, string> = {
        HOOK_URL: envConfig.hookUrl,
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
          setSaveResult({ success: true, message: '通知设置已保存' })
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
    <div className="space-y-6 max-w-5xl mx-auto pb-12">
      <PageHeader
        icon={<BiCodeAlt className="text-xl" />}
        title="环境变量配置"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button
              onClick={() => handleSave(false)}
              size="sm"
              disabled={saving}
              className="bg-primary text-primary-foreground hover:bg-primary/90"
            >
              <BiSave className="mr-1" /> 保存通知
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
        <AiConnectionCard />

        {/* 企业微信 Webhook */}
        <Card className="bg-card border-border text-foreground shadow-none rounded-lg">
          <CardHeader className="flex flex-row items-center justify-between pb-3">
            <CardTitle className="text-base flex items-center gap-2">
              <BiLinkExternal className="text-muted-foreground" />
              企业微信通知
            </CardTitle>
            <button
              type="button"
              id="botIsSend"
              aria-label="企业微信发送开关"
              onClick={() => setEnvConfig({ ...envConfig, botIsSend: envConfig.botIsSend ? 0 : 1 })}
              className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 border border-input ${
                envConfig.botIsSend ? 'bg-primary' : 'bg-input'
              }`}
            >
              <span
                className={`pointer-events-none inline-block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${
                  envConfig.botIsSend ? 'translate-x-5' : 'translate-x-0'
                }`}
              />
            </button>
          </CardHeader>
          <CardContent>
            <div className="space-y-1.5">
              <Label htmlFor="hookUrl" className="text-xs">Webhook URL</Label>
              <Input
                id="hookUrl"
                type="text"
                value={envConfig.hookUrl}
                onChange={(e) => setEnvConfig({ ...envConfig, hookUrl: e.target.value })}
                placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
              />
            </div>
          </CardContent>
        </Card>

        {/* 保存结果弹框 */}
        {showSaveDialog && saveResult && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" role="dialog" aria-modal="true">
            <Card className="bg-card border-border text-foreground rounded-lg shadow-none w-[92%] max-w-sm">
              <CardHeader className="pb-2">
                <CardTitle className="text-base flex items-center gap-2">
                  <BiSave className={saveResult.success ? 'text-emerald-500' : 'text-destructive'} />
                  {saveResult.success ? '保存成功' : '操作未完成'}
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
                    确定
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
