'use strict'

const clone = value => globalThis.structuredClone(value)
const PROJECT_ROUTE = /(?:\/|%2f)api(?:\/|%2f)v1(?:\/|%2f)projects(?:\/|%2f)/i
const TOKEN = /testhub-file:([A-Z0-9]{26})/g
const DOWNLOAD = /\/api\/v1\/projects\/([A-Z0-9]{26})\/files\/([A-Z0-9]{26})\/download(?=$|[\s"'<>?#)\]])/g
const blocked = item => PROJECT_ROUTE.test(JSON.stringify(item.definition))
const withinExternalUrl = (text, offset) => {
  const segment = text.slice(0, offset).split(/[\s"'<>()[\]]/).at(-1)
  return /(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(segment)
}

function mapStrings(value, change) {
  if (typeof value === 'string') return change(value)
  if (Array.isArray(value)) return value.map(part => mapStrings(part, change))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, part]) => [key, mapStrings(part, change)]))
  return value
}

class ProjectFileLinks {
  constructor(binding, context) {
    this.binding = binding
    this.byKey = new Map()
    this.byId = { local: new Map(), remote: new Map() }
    if (!context) return
    if (context.sourceProject !== binding.source.id || context.targetProject !== binding.target.id) throw new Error('项目文件同步范围与资料范围不同，文件链接已暂停。')
    for (const link of context.links) {
      if (this.byKey.has(link.key) || this.byId.local.has(link.localId) || this.byId.remote.has(link.remoteId)) throw new Error('项目文件对应关系重复，文件链接已暂停。')
      this.byKey.set(link.key, link)
      this.byId.local.set(link.localId, link)
      this.byId.remote.set(link.remoteId, link)
    }
  }

  logical(item, side) {
    const project = side === 'local' ? this.binding.source.id : this.binding.target.id
    const result = clone(item)
    result.definition = mapStrings(result.definition, value => {
      // A literal transport token in user content must never gain file authority.
      let text = value.replace(TOKEN, '/api/v1/projects/invalid-file-token')
      text = text.replace(DOWNLOAD, (match, routeProject, fileId, offset, whole) => {
        const previous = offset ? whole[offset - 1] : ''
        if (/[\p{L}\p{N}_:/%.\\-]/u.test(previous) || withinExternalUrl(whole, offset) || routeProject !== project) return match
        const link = this.byId[side].get(fileId)
        return link ? `testhub-file:${link.key}` : match
      })
      return text
    })
    return result
  }

  physical(item, side) {
    if (blocked(item)) throw new Error('正文包含未确认的本端项目或文件链接；该资料暂停同步。')
    const project = side === 'local' ? this.binding.source.id : this.binding.target.id
    const result = clone(item)
    result.definition = mapStrings(result.definition, value => value.replace(TOKEN, (_match, key) => {
      const link = this.byKey.get(key)
      if (!link) throw new Error('正文引用的文件未配对、已删除或内容已变化；该资料暂停同步。')
      return `/api/v1/projects/${project}/files/${side === 'local' ? link.localId : link.remoteId}/download`
    }))
    return result
  }
}

module.exports = { ProjectFileLinks, blocked }
