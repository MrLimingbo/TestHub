const platform = process.argv[2]
const required = platform === 'mac'
  ? ['CSC_LINK', 'CSC_KEY_PASSWORD', 'APPLE_ID', 'APPLE_APP_SPECIFIC_PASSWORD', 'APPLE_TEAM_ID']
  : platform === 'win'
    ? ['CSC_LINK', 'CSC_KEY_PASSWORD']
    : []

if (required.length === 0) {
  throw new Error('Usage: node scripts/desktop/verify-signing.mjs <mac|win>')
}

const missing = required.filter((name) => !process.env[name])
if (missing.length > 0) {
  throw new Error(`${platform} 发布构建需要以下 GitHub Actions Secret：${missing.join(', ')}`)
}
