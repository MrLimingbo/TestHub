const message = 'config.delayMillis 必须是 0 到 600000 之间的精确整数数值'

// Inspect decimal digits before Number conversion, including scientific notation.
export function parseWaitDelayInput(raw: string): { value?: number; error?: string } {
  const match = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw.trim())
  if (!match) return { error: message }
  const fraction = match[3] ?? ''
  let digits = (match[2]! + fraction).replace(/^0+/, '')
  if (!digits) return { value: 0 }
  if (match[1] === '-') return { error: message }
  const scale = BigInt(fraction.length) - BigInt(match[4] ?? '0')
  if (scale > 0n) {
    if (scale >= BigInt(digits.length)) return { error: message }
    const removed = Number(scale)
    if (!/^0+$/.test(digits.slice(-removed))) return { error: message }
    digits = digits.slice(0, -removed)
  } else if (scale < 0n) {
    if (BigInt(digits.length) - scale > 6n) return { error: message }
    digits += '0'.repeat(Number(-scale))
  }
  if (digits.length > 6) return { error: message }
  const value = Number(digits)
  return value <= 600_000 ? { value } : { error: message }
}

export function validateWaitSteps(steps: { id: string; type: string; config: Record<string, unknown> }[]) {
  for (const step of steps) {
    if (step.type !== 'WAIT') continue
    const value = step.config.delayMillis
    if (Object.keys(step.config).length !== 1 || typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > 600_000) {
      throw new Error(`步骤 ${step.id} 的 ${message}`)
    }
  }
}
