export const DEFAULT_REPRODUCTION_TEMPLATE = [
  '<p><strong>[步骤]</strong></p>',
  '<p><br></p>',
  '<p><strong>[结果]</strong></p>',
  '<p><br></p>',
  '<p><strong>[期望]</strong></p>',
].join('')

const allowedTags = new Set([
  'A', 'B', 'BLOCKQUOTE', 'BR', 'CODE', 'DIV', 'EM', 'H1', 'H2', 'H3', 'I', 'IMG', 'LI', 'OL', 'P', 'PRE',
  'S', 'SPAN', 'STRONG', 'TABLE', 'TBODY', 'TD', 'TH', 'THEAD', 'TR', 'U', 'UL',
])

function safeUrl(value: string): string | null {
  try {
    const url = new URL(value, window.location.origin)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : null
  } catch {
    return null
  }
}

export function sanitizeRichText(value: string): string {
  if (!value) return ''
  const template = document.createElement('template')
  template.innerHTML = value
  const elements = [...template.content.querySelectorAll('*')]
  for (const element of elements) {
    if (!allowedTags.has(element.tagName)) {
      element.replaceWith(...element.childNodes)
      continue
    }
    const attributes = [...element.attributes]
    for (const attribute of attributes) element.removeAttribute(attribute.name)
    if (element.tagName === 'A') {
      const href = safeUrl((attributes.find((item) => item.name === 'href')?.value ?? '').trim())
      if (href) {
        element.setAttribute('href', href)
        element.setAttribute('target', '_blank')
        element.setAttribute('rel', 'noopener noreferrer')
      }
    }
    if (element.tagName === 'IMG') {
      const src = safeUrl((attributes.find((item) => item.name === 'src')?.value ?? '').trim())
      if (!src) {
        element.remove()
        continue
      }
      element.setAttribute('src', src)
      element.setAttribute('alt', attributes.find((item) => item.name === 'alt')?.value ?? '')
    }
    if (['TD', 'TH'].includes(element.tagName)) {
      for (const name of ['colspan', 'rowspan']) {
        const raw = attributes.find((item) => item.name === name)?.value ?? ''
        if (/^[1-9]\d?$/.test(raw)) element.setAttribute(name, raw)
      }
    }
  }
  return template.innerHTML.trim()
}

export function richTextPlainText(value: string): string {
  const template = document.createElement('template')
  template.innerHTML = sanitizeRichText(value)
  return (template.content.textContent ?? '').replace(/\u00a0/g, ' ').trim()
}

export function richTextForEditor(value: string): string {
  if (!value) return ''
  if (/<[a-z][\s\S]*>/i.test(value)) return sanitizeRichText(value)
  const container = document.createElement('div')
  value.split(/\r?\n/).forEach((line) => {
    const paragraph = document.createElement('p')
    paragraph.textContent = line
    if (!line) paragraph.appendChild(document.createElement('br'))
    container.appendChild(paragraph)
  })
  return container.innerHTML
}
