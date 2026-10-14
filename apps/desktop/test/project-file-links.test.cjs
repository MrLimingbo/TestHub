'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const { ProjectFileLinks, blocked } = require('../app/src/project-file-links.cjs')

const source = 'A'.repeat(26), target = 'B'.repeat(26), local = 'L'.repeat(26), remote = 'R'.repeat(26), key = 'K'.repeat(26)
const binding = { source: { id: source }, target: { id: target } }
const context = { sourceProject: source, targetProject: target, links: [{ key, localId: local, remoteId: remote }] }
const path = (project, id) => `/api/v1/projects/${project}/files/${id}/download`
const item = description => ({ kind: 'PLAN', name: 'PLAN-1', definition: { description, caseCodes: [] } })

test('paired canonical file links compare by origin and render physical IDs per endpoint', () => {
  const links = new ProjectFileLinks(binding, context)
  const a = links.logical(item(`见 ${path(source, local)})`), 'local')
  const b = links.logical(item(`见 ${path(target, remote)})`), 'remote')
  assert.deepEqual(a, b)
  assert.equal(a.definition.description, `见 testhub-file:${key})`)
  assert.equal(links.physical(a, 'remote').definition.description, `见 ${path(target, remote)})`)
  assert.equal(links.physical(b, 'local').definition.description, `见 ${path(source, local)})`)
})

test('unpaired, deleted, external and encoded project links cannot gain a mapping', () => {
  const links = new ProjectFileLinks(binding, context)
  for (const text of [path(source, 'X'.repeat(26)), path(target, remote),
    `https://other.test${path(source, local)}`, `x${path(source, local)}`,
    `https://other.test/?next=${path(source, local)}`,
    `%2Fapi%2Fv1%2Fprojects%2F${source}%2Ffiles%2F${local}%2Fdownload`,
    `%2Fapi%2Fv1%2Fprojects/${source}/files/${local}/download`,
    `testhub-file:${key}`]) {
    const raw = item(text)
    const normalized = links.logical(raw, 'local')
    assert.equal(blocked(normalized), true, text)
    assert.throws(() => links.physical(normalized, 'remote'), /未确认/, text)
  }
  const deleted = new ProjectFileLinks(binding, { ...context, links: [] })
  assert.equal(blocked(deleted.logical(item(path(source, local)), 'local')), true)
  assert.throws(() => deleted.physical(item(`testhub-file:${key}`), 'remote'), /未配对/)
})

test('mismatched scope or duplicate physical IDs reject the mapping', () => {
  assert.throws(() => new ProjectFileLinks(binding, { ...context, targetProject: source }), /范围不同/)
  assert.throws(() => new ProjectFileLinks(binding, { ...context, links: [...context.links, { key: 'Z'.repeat(26), localId: local, remoteId: 'S'.repeat(26) }] }), /重复/)
})
