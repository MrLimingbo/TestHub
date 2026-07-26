'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const {
  isTrustedServerUrl,
  normalizeServerUrl,
  readPreferences,
  writePreferences,
} = require('../app/src/desktop-core.cjs')

test('normalizes an HTTPS TestHub web origin', () => {
  assert.equal(normalizeServerUrl('https://TestHub.example.com/'), 'https://testhub.example.com')
})

test('allows HTTP only for explicit local development hosts', () => {
  assert.equal(normalizeServerUrl('http://127.0.0.1:5173'), 'http://127.0.0.1:5173')
  assert.throws(() => normalizeServerUrl('http://testhub.example.com'), /必须使用 HTTPS/)
})

test('rejects URLs with credentials, paths, queries, or fragments', () => {
  for (const value of [
    'https://alice:secret@testhub.example.com',
    'https://testhub.example.com/console',
    'https://testhub.example.com?debug=true',
    'https://testhub.example.com#login',
  ]) {
    assert.throws(() => normalizeServerUrl(value))
  }
})

test('only trusts same-origin navigation', () => {
  assert.equal(isTrustedServerUrl('https://testhub.example.com/dashboard', 'https://testhub.example.com'), true)
  assert.equal(isTrustedServerUrl('https://outside.example.com', 'https://testhub.example.com'), false)
})

test('persists a normalized server preference without retaining other values', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-desktop-'))
  try {
    await writePreferences(directory, 'https://testhub.example.com/')
    assert.deepEqual(await readPreferences(directory), { serverUrl: 'https://testhub.example.com' })
  } finally {
    await fs.rm(directory, { recursive: true, force: true })
  }
})
