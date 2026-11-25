import { describe, expect, it } from 'vitest'
import { parseCaseDrafts, readTurnEvents } from '@/utils/aiTesting'

function stream(text: string) {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (let offset = 0; offset < bytes.length; offset += 3) controller.enqueue(bytes.slice(offset, offset + 3))
      controller.close()
    },
  })
}

describe('AI conversation stream', () => {
  it('decodes fragmented UTF-8, CRLF and terminal events', async () => {
    const types: string[] = []
    const deltas: string[] = []
    await readTurnEvents(stream('event:delta\r\ndata:{"text":"第一轮"}\r\n\r\n'
      + 'event:done\r\ndata:{"status":"COMPLETED"}\r\n\r\n'), event => {
      types.push(event.type)
      if (event.type === 'delta') deltas.push(event.data.text)
    })
    expect(types).toEqual(['delta', 'done'])
    expect(deltas).toEqual(['第一轮'])
  })

  it('reports a stream interrupted before a terminal event', async () => {
    await expect(readTurnEvents(stream('event:delta\ndata:{"text":"partial"}\n\n'), () => {}))
      .rejects.toThrow('对话连接已中断')
  })

  it('extracts editable case drafts from a fenced model reply', () => {
    const formatted = '```json\n{"cases":[{"title":"折扣测试","steps":"核销","expectedResult":"成功"}]}\n```'
    expect(parseCaseDrafts(formatted)).toEqual([{
      title: '折扣测试', priority: 'P2', precondition: '', steps: '核销', expectedResult: '成功',
    }])
    expect(parseCaseDrafts('{"cases":[null]}')).toEqual([])
  })
})
