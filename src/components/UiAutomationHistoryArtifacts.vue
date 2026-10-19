<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
interface Artifact { key:string;sourceArtifactId:string;artifactType:'SCREENSHOT'|'TRACE'|'VIDEO';originalName:string;contentType:string;sizeBytes:number;checksum:string;archivedAt:string }
const props=defineProps<{projectId:string;historyKey:string}>()
const files=ref<Artifact[]>([]),loading=ref(false),error=ref(''),page=ref(1),preview=ref<Artifact|null>(null)
let sequence=0
const visible=computed(()=>files.value.slice((page.value-1)*12,page.value*12))
const route=(file:Artifact,action='download')=>`/api/v1/projects/${encodeURIComponent(props.projectId)}/ui-automation/artifacts/${encodeURIComponent(file.key)}/${action}`
const labels={SCREENSHOT:'截图',TRACE:'Trace',VIDEO:'视频'}
const size=(v:number)=>v<1024?`${v} B`:v<1024*1024?`${(v/1024).toFixed(1)} KiB`:`${(v/1024/1024).toFixed(1)} MiB`
async function load(){
  const token=++sequence,project=props.projectId,history=props.historyKey
  files.value=[];preview.value=null;page.value=1;error.value='';loading.value=Boolean(project&&history)
  if(!project||!history)return
  try{
    const response=await fetch(`/api/v1/projects/${encodeURIComponent(project)}/ui-automation/history/${encodeURIComponent(history)}/artifacts`,{credentials:'include'})
    const body=await response.json();if(!response.ok)throw new Error(body.message||`报告产物读取失败（HTTP ${response.status}）`)
    if(token!==sequence||project!==props.projectId||history!==props.historyKey)return
    const rows=body.data
    if(!Array.isArray(rows)||rows.length>2000||rows.some(f=>!f||!['SCREENSHOT','TRACE','VIDEO'].includes(f.artifactType)
      ||typeof f.sourceArtifactId!=='string'||! /^[A-Z0-9]{26}$/.test(f.sourceArtifactId)||f.key!==history+'.'+f.sourceArtifactId
      ||f.originalName!==(f.artifactType==='SCREENSHOT'?'screenshot.png':f.artifactType==='TRACE'?'trace.zip':'video.webm')
      ||f.contentType!==(f.artifactType==='SCREENSHOT'?'image/png':f.artifactType==='TRACE'?'application/zip':'video/webm')
      ||!Number.isSafeInteger(f.sizeBytes)||f.sizeBytes<0||f.sizeBytes>25*1024*1024||! /^[a-f0-9]{64}$/.test(f.checksum)
      ||typeof f.archivedAt!=='string'||!Number.isFinite(Date.parse(f.archivedAt)))||new Set(rows.map(f=>f.key)).size!==rows.length)throw new Error('报告产物列表不完整，请刷新后核对')
    files.value=rows
  }catch(failure){if(token===sequence)error.value=failure instanceof Error?failure.message:'无法加载报告产物'}
  finally{if(token===sequence)loading.value=false}
}
watch(()=>[props.projectId,props.historyKey],()=>{void load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{sequence++})
</script>
<template>
  <section class="history-artifacts" aria-label="Web UI 原始报告产物">
    <div class="heading"><h4>原始截图／Trace／视频</h4><a-button size="small" :loading="loading" :disabled="!projectId||!historyKey" @click="load">刷新报告产物</a-button></div>
    <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
    <a-spin :loading="loading">
      <p v-if="!loading&&!error&&!files.length" class="note">暂无原始产物，可在桌面单独确认“Web UI 报告产物同步”。</p>
      <template v-if="files.length">
        <p class="note">原始内容可能含敏感页面信息。传输和下载核对字节及 SHA-256；预览仅在点击后加载。</p>
        <ul><li v-for="file in visible" :key="file.key"><a :href="route(file)" download>下载{{ labels[file.artifactType] }}</a><a-button v-if="file.artifactType!=='TRACE'" size="mini" @click="preview=file">预览{{ labels[file.artifactType] }}</a-button><span>{{ size(file.sizeBytes) }} · {{ new Date(file.archivedAt).toLocaleString() }}</span><code>来源文件 {{ file.sourceArtifactId }} · SHA-256 {{ file.checksum }}</code></li></ul>
        <a-pagination v-if="files.length>12" v-model:current="page" :total="files.length" :page-size="12" size="small" />
        <section v-if="preview" class="preview" aria-label="报告产物预览"><div class="heading"><strong>{{ labels[preview.artifactType] }} · {{ preview.sourceArtifactId }}</strong><a-button size="mini" @click="preview=null">关闭预览</a-button></div><a-image v-if="preview.artifactType==='SCREENSHOT'" :src="route(preview,'view')" :alt="'原始截图 '+preview.sourceArtifactId" /><video v-else controls preload="metadata" :src="route(preview,'view')">当前浏览器不支持视频预览。</video></section>
      </template>
    </a-spin>
  </section>
</template>
<style scoped>
.history-artifacts{margin:20px 0;border-top:1px solid var(--color-border-2);padding-top:12px}.heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.heading h4{margin:0}.note,li span{font-size:12px;color:var(--color-text-3)}ul{list-style:none;padding:0}li{display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;margin:12px 0}code{width:100%;overflow-wrap:anywhere;font-size:11px}.preview{margin-top:16px}.preview :deep(img),video{max-width:100%;max-height:360px}.preview .heading{margin-bottom:10px}
</style>
