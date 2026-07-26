import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const templateUrl = new URL('../../../ci/gitlab/testhub-api-automation.yml', import.meta.url)

test('GitLab CI template runs the one-command gate and retains its report artifact on failure', async () => {
  const template = await readFile(templateUrl, 'utf8')

  assert.match(template, /^testhub_api_automation:/m)
  assert.match(template, /image: node:22-alpine/)
  assert.match(template, /TESTHUB_URL/)
  assert.match(template, /TESTHUB_PROJECT_ID/)
  assert.match(template, /TESTHUB_SCENARIO_ID/)
  assert.match(template, /TESTHUB_TOKEN/)
  assert.match(template, /execution run/)
  assert.match(template, /--report-output "\.\/artifacts\/testhub-execution-report\.json"/)
  assert.match(template, /when: always/)
  assert.match(template, /artifacts\/testhub-execution-report\.json/)
  assert.equal(template.includes('thp_'), false)
})
