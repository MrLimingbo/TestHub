'use strict'
const { FunctionalDefectPeopleSync, FunctionalDefectPeopleSyncStore } = require('./functional-defect-people-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function describe(item,binding,side) {
  if(!item)return '缺陷不存在'
  const label=id=>{if(!id)return '暂不指派';const p=binding.members.find(p=>p.local.id===id);return p?`${p[side].name}（${p[side].account}）`:'未匹配的成员'}
  return `${item.name} · 负责人：${label(item.definition.assigneeId)} · 抄送：${item.definition.ccUserIds.map(label).join('、')||'无'}`
}
function installFunctionalDefectPeopleSync(options) {
  return installProjectSync(options, {
    Client: FunctionalDefectPeopleSync, Store: FunctionalDefectPeopleSyncStore, channel: 'functional-defect-people-sync', page: 'functional-defect-people-sync.html',
    filename: 'functional-defect-people-sync.bin', title: '缺陷人员同步', transport: { label: '缺陷人员', limit: 4 * 1024 * 1024 },
    preview: (client,value,server) => value?.mode === 'members' ? client.members(value.sourceId,value.targetId,server) : client.preview(value?.sourceId,value?.targetId,server,value?.pairs),
    planDetail: p => `服务：${p.binding.server}\n成员对应：\n${p.binding.members.map(m=>`${m.local.name}（${m.local.account} · ${m.local.email||'无邮箱'}） ↔ ${m.remote.name}（${m.remote.account} · ${m.remote.email||'无邮箱'}）`).join('\n')}\n首次上传 ${p.counts.upload} 条、下载 ${p.counts.download} 条，${p.counts.conflictCount} 条需核对。\n仅同步负责人和抄送，不会创建账号或授予权限。缺失、停用或未配对人员暂停相关缺陷；清空人员也作为完整修改同步。负责人变化记录目标端实际操作账号；抄送变化保留原指派人。状态、历史、正文和关联保留各端。人员账号与邮箱仅供核对，保存于本机加密绑定；仅目标成员标识写入账号服务。最多 100 条缺陷、3 MiB。`,
    unbindDetail: '两端缺陷人员均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: (c,binding) => `本地：${describe(c.local,binding,'local')}\n网页：${describe(c.remote,binding,'remote')}\n选择将替换完整负责人和抄送。负责人变化记录目标端实际操作账号；历史和状态保留。确认期间人员或资料变化时需重新核对。`,
  })
}
module.exports = { installFunctionalDefectPeopleSync }
