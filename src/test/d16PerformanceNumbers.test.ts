import { expect, it } from 'vitest'
import { performanceIntegerInput, performancePercentInput, performanceNumberText, performanceGroupNumbers, performanceNumberError } from '@/utils/performanceNumberInput'
it('TC-D16PERF-57/58: integer text is checked before rounding', () => {
  for (const raw of ['10', '10.0', '1e1', '100e-1']) expect(performanceIntegerInput(raw, 1, 100000, 'threadCount')).toBe(10)
  for (const raw of ['1.5', '1.0000000000000001', '1e10000', '1e-10000', '100001', '-1', 'NaN', '"10"']) expect(() => performanceIntegerInput(raw, 1, 100000, 'threadCount')).toThrow()
  const text = performanceNumberText({ threadCount: 10, rampUpSeconds: 0, durationSeconds: 10 })
  expect(performanceGroupNumbers(text, 'RAMP_UP', 1).targetTps).toBeUndefined()
  text.threadCount = ''; expect(() => performanceGroupNumbers(text, 'RAMP_UP', 1)).toThrow('线程组 2')
  text.stepCount = '1.5'; expect(performanceNumberError(text, 'stepCount', 'RAMP_UP')).toBe('')
})
it('TC-D16PERF-59/60: decimal percentage precision and optional semantics remain distinct', () => {
  for (const [raw, expected] of [['2.50', 2.5], ['25e-1', 2.5], ['0', 0], ['100', 100], ['-0', 0]] as const) expect(performancePercentInput(raw)).toBe(expected)
  for (const raw of ['2.501', '2.500', '0.000', '100.01', '-0.01', '1e10000', '1e-10000', 'NaN', '"2.5"']) expect(() => performancePercentInput(raw)).toThrow('errorThresholdPercent')
  const text = performanceNumberText({ threadCount: 10, rampUpSeconds: 0, durationSeconds: 10 }); expect(performanceGroupNumbers(text, 'RAMP_UP', 0).errorThresholdPercent).toBeUndefined()
})
