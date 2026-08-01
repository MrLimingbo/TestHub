import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CodeEditor from '@/components/CodeEditor.vue'

const monaco = vi.hoisted(() => {
  let value = ''
  let changeListener: (() => void) | undefined
  const model = {}
  const instance = {
    dispose: vi.fn(),
    getModel: vi.fn(() => model),
    getValue: vi.fn(() => value),
    onDidChangeModelContent: vi.fn((listener: () => void) => {
      changeListener = listener
      return { dispose: vi.fn() }
    }),
    setValue: vi.fn((nextValue: string) => {
      value = nextValue
      changeListener?.()
    }),
  }
  const create = vi.fn((_host: HTMLElement, options: { value: string }) => {
    value = options.value
    return instance
  })
  const setModelLanguage = vi.fn()
  return {
    create,
    instance,
    model,
    reset() {
      value = ''
      changeListener = undefined
      create.mockClear()
      setModelLanguage.mockClear()
      Object.values(instance).forEach((item) => {
        if (typeof item === 'function' && 'mockClear' in item) item.mockClear()
      })
    },
    setModelLanguage,
    userInput(nextValue: string) {
      value = nextValue
      changeListener?.()
    },
  }
})

vi.mock('monaco-editor/esm/vs/editor/editor.api', () => ({
  editor: {
    create: monaco.create,
    setModelLanguage: monaco.setModelLanguage,
  },
}))
vi.mock('monaco-editor/esm/vs/editor/editor.worker?worker', () => ({ default: class EditorWorker {} }))
vi.mock('monaco-editor/esm/vs/language/json/json.worker?worker', () => ({ default: class JsonWorker {} }))

describe('CodeEditor', () => {
  beforeEach(() => monaco.reset())

  it('applies external values but does not reset the editor for its own v-model update', async () => {
    const wrapper = mount(CodeEditor, { props: { modelValue: '{"first":true}' } })

    await wrapper.setProps({ modelValue: '{"external":true}' })
    expect(monaco.instance.setValue).toHaveBeenCalledOnce()
    expect(monaco.instance.setValue).toHaveBeenLastCalledWith('{"external":true}')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    monaco.userInput('{"typed":true}')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['{"typed":true}'])

    await wrapper.setProps({ modelValue: '{"typed":true}' })
    expect(monaco.instance.setValue).toHaveBeenCalledOnce()

    wrapper.unmount()
    expect(monaco.instance.dispose).toHaveBeenCalledOnce()
  })

  it('updates the Monaco model language when the language prop changes', async () => {
    const wrapper = mount(CodeEditor, { props: { modelValue: '{}', language: 'json' } })
    await wrapper.setProps({ language: 'graphql' })
    expect(monaco.setModelLanguage).toHaveBeenCalledWith(monaco.model, 'graphql')
  })
})
