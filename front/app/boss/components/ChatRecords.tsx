'use client'

import { Button } from '@/components/ui/button'

export interface ChatRecordItem {
  id: string
  conversationId: string
  lastInboundMessageId: string
  state: 'blocked' | 'unknown' | 'failed' | string
  reasonCode: string
  missingFacts?: string[]
  sourceText?: string
  replyText?: string
  profileVersion?: string
  createdAt?: string
}

interface ChatRecordsProps {
  records: ChatRecordItem[]
  onReconcile: (id: string, confirmed: boolean) => void
  busy?: boolean
}

const stateLabels: Record<string, { label: string; className: string }> = {
  blocked: { label: '已阻断', className: 'text-amber-600 bg-amber-50 dark:bg-amber-950/40 border-amber-200' },
  unknown: { label: '待核实', className: 'text-blue-600 bg-blue-50 dark:bg-blue-950/40 border-blue-200' },
  failed: { label: '发送失败', className: 'text-red-600 bg-red-50 dark:bg-red-950/40 border-red-200' },
}

export default function ChatRecords({ records, onReconcile, busy = false }: ChatRecordsProps) {
  if (!records || records.length === 0) {
    return (
      <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
        暂无需要处理的记录
      </div>
    )
  }

  return (
    <div className="space-y-3">
      {records.map((record) => {
        const stateInfo = stateLabels[record.state] || {
          label: record.state,
          className: 'text-muted-foreground bg-muted border-border',
        }
        const isUnknown = record.state === 'unknown'
        const truncatedSource = record.sourceText
          ? record.sourceText.length > 200
            ? record.sourceText.slice(0, 200) + '...'
            : record.sourceText
          : '（无消息内容）'

        return (
          <div key={record.id} className="space-y-2 rounded-lg border p-4 text-sm bg-card">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <span
                  className={`inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-medium ${stateInfo.className}`}
                >
                  {stateInfo.label}
                </span>
                <span className="text-xs text-muted-foreground font-mono">
                  原因码: {record.reasonCode || '无'}
                </span>
              </div>
              {record.createdAt && (
                <span className="text-xs text-muted-foreground">
                  {new Date(record.createdAt).toLocaleString()}
                </span>
              )}
            </div>

            <div className="rounded bg-muted/40 p-2 text-xs text-muted-foreground">
              <span className="font-semibold text-foreground">对方消息：</span>
              <span className="whitespace-pre-wrap">{truncatedSource}</span>
            </div>

            {record.missingFacts && record.missingFacts.length > 0 && (
              <p className="text-xs text-amber-600">
                缺失信息：{record.missingFacts.join('、')}
              </p>
            )}

            {isUnknown && (
              <div className="flex flex-wrap items-center justify-end gap-2 pt-2 border-t mt-2">
                <span className="text-xs text-muted-foreground mr-auto">
                  此记录状态为未知，核实实际发送情况：
                </span>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  className="h-8 text-xs text-green-600 hover:text-green-700 hover:bg-green-50"
                  onClick={() => onReconcile(record.id, true)}
                >
                  已核对：已发送
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  className="h-8 text-xs text-muted-foreground hover:text-foreground"
                  onClick={() => onReconcile(record.id, false)}
                >
                  已核对：未发送
                </Button>
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
