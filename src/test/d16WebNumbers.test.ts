import { expect, it } from 'vitest'
import { webIntegerInput, webOptionalInteger, webStepTimeout } from '@/utils/webIntegerInput'
it('TC-D16WEB-46/47/48: original decimal text is checked before rounding', () => {
  for (const raw of ['800', '800.0', '8e2']) expect(webIntegerInput(raw, 320, 3840, 'viewportWidth')).toBe(800)
  for (const raw of ['800.5', '800.0000000000000001', '1e10000', '1e-10000', '3841', 'NaN', '"800"']) expect(() => webIntegerInput(raw, 320, 3840, 'viewportWidth')).toThrow()
  expect(webStepTimeout('1.0', 'WAIT')).toBe(1); expect(() => webStepTimeout('99', 'FILL')).toThrow('timeoutMillis')
})
it('TC-D16WEB-49/50: optional defaults and WAIT required fields remain distinct', () => {
  expect(webOptionalInteger('', 320, 3840, 'viewportWidth')).toBeUndefined()
  expect(webOptionalInteger('', 10, 3600, 'timeoutSeconds', 300)).toBe(300)
  expect(webStepTimeout('', 'FILL')).toBeUndefined(); expect(() => webStepTimeout('', 'WAIT')).toThrow('timeoutMillis')
  expect(() => webStepTimeout('50', 'FILL')).toThrow(); expect(webStepTimeout('50', 'WAIT')).toBe(50)
})
