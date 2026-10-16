'use strict'

function macSignature(output, teamId) {
  if (!/^[A-Z0-9]{10}$/.test(teamId || '')) throw new Error('缺少有效的 Apple Team ID。')
  const values = key => [...output.matchAll(new RegExp('^' + key + '=(.*)$', 'gm'))].map(match => match[1].trim())
  const one = key => { const items = values(key); if (items.length !== 1) throw new Error('macOS 签名元数据不完整。'); return items[0] }
  if (one('Identifier') !== 'io.testhub.desktop' || one('TeamIdentifier') !== teamId) throw new Error('macOS 应用标识或签名团队不匹配。')
  if (values('Signature').includes('adhoc') || !values('Authority').some(value => value.startsWith('Developer ID Application: ')))
    throw new Error('macOS 应用必须使用 Developer ID Application 签名。')
  const flags = /^.*\bflags=0x([a-f0-9]+)\b.*$/im.exec(output)
  if (!flags || !(Number.parseInt(flags[1], 16) & 0x10000)) throw new Error('macOS 应用未启用 Hardened Runtime。')
  if (!one('Timestamp') || /^(none|not set)$/i.test(one('Timestamp'))) throw new Error('macOS 应用缺少安全时间戳。')
  return { applicationId: 'io.testhub.desktop', expectedTeamMatched: true, developerIdApplication: true, hardenedRuntime: true, secureTimestampPresent: true }
}

function windowsSignatures(records) {
  if (!Array.isArray(records) || records.length !== 2 || new Set(records.map(record => record?.role)).size !== 2)
    throw new Error('Windows 签名检查必须同时包含应用与安装器。')
  const certificates = []
  for (const role of ['application', 'installer']) {
    const record = records.find(item => item?.role === role)
    if (!record || record.status !== 'Valid' || record.signatureType !== 'Authenticode') throw new Error('Windows 应用或安装器的 Authenticode 签名无效。')
    if (!/^[a-f0-9]{64}$/i.test(record.certificateSha256 || '') || record.codeSigningEku !== true || record.timestampPresent !== true)
      throw new Error('Windows 签名缺少代码签名证书或时间戳。')
    certificates.push(record.certificateSha256.toLowerCase())
  }
  if (certificates[0] !== certificates[1]) throw new Error('Windows 应用与安装器的签名证书不一致。')
  return { authenticodeValid: true, codeSigningEku: true, timestampPresent: true, matchingSigner: true, certificateSha256: certificates[0] }
}

module.exports = { macSignature, windowsSignatures }
