<script setup lang="ts">
import { NAlert, NButton } from 'naive-ui'
import { useAuth } from '../stores/auth'
import { useAction } from '../composables/common'
import { retrieveCurl, answerCurl, javaExample, errors } from '../domain/guide'
import PageHeader from '../components/PageHeader.vue'
const auth = useAuth()
const { copy } = useAction()
const examples = [
  { title: 'curl · /retrieve', code: retrieveCurl },
  { title: 'curl · /answer', code: answerCurl },
  { title: 'Java 17 HttpClient · /retrieve', code: javaExample },
]
</script>
<template>
  <PageHeader title="使用说明" description="从配置资源到接入业务，开始使用 DocQuery。"
    ><a class="button-link" href="/admin/docs/docquery-service-api.openapi.yaml" download
      >下载 OpenAPI</a
    ><a class="button-link" href="/admin/docs/docquery-service-api.postman_collection.json" download
      >下载 Postman Collection</a
    ></PageHeader
  ><NAlert type="info" title="接入边界" class="space-bottom"
    >业务系统负责最终用户登录和业务权限；DocQuery
    负责租户隔离、应用凭证、知识库授权和查询审计。凭证应保存在业务后端。</NAlert
  >
  <section v-if="auth.admin?.role === '1'" class="panel">
    <h2>平台管理员先完成租户开通</h2>
    <p>创建租户并设置首个管理员，通过受控渠道交付账号；租户管理员登录后配置应用、知识库与授权。</p>
    <RouterLink to="/tenants">进入租户管理 →</RouterLink>
    <p>租户详情支持新增、启停管理员及重置密码。平台管理员不能绕过租户身份直接操作应用和知识库。</p>
  </section>
  <section class="panel">
    <h2>五步完成接入</h2>
    <ol class="guide-steps">
      <li>
        <strong>创建应用</strong>
        <p>配置环境和状态</p>
      </li>
      <li>
        <strong>创建 Credential</strong>
        <p>立即保存完整凭证</p>
      </li>
      <li>
        <strong>创建知识库</strong>
        <p>上传文档并等待就绪</p>
      </li>
      <li>
        <strong>授予 READ</strong>
        <p>建立应用与知识库授权</p>
      </li>
      <li>
        <strong>业务后端调用</strong>
        <p>携带凭证与幂等键</p>
      </li>
    </ol>
    <p>在知识库详情点击“复制 ID”，将 KnowledgeBase ID 作为受控配置交给业务应用。</p>
  </section>
  <section class="panel">
    <h2>服务契约</h2>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>POST 路径</th>
            <th>用途</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><code>/api/v1/service/knowledge-bases/{knowledgeBaseId}/retrieve</code></td>
            <td>返回排序后的原文证据</td>
          </tr>
          <tr>
            <td><code>/api/v1/service/knowledge-bases/{knowledgeBaseId}/answer</code></td>
            <td>生成受控单轮回答及引用</td>
          </tr>
          <tr>
            <td><code>/api/v1/service/knowledge-bases/{knowledgeBaseId}/answer/stream</code></td>
            <td>逐段生成回答，结束后返回正式引用</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p>
      <code>Authorization: Bearer dq_app_...</code> · <code>Idempotency-Key: UUID</code> ·
      <code>Content-Type: application/json</code>
    </p>
    <p>
      流式接口增加 <code>Accept: text/event-stream</code>。生成中的文字尚未完成校验；只有
      <code>done</code> 事件中的结果及引用是正式结果。中断后可用原幂等键重试，不自动重连。
    </p>
    <p>可选上下文：<code>X-DocQuery-Trace-Id</code>、<code>X-DocQuery-Actor-Ref</code>。</p>
    <p>记录响应头 <code>X-DocQuery-Request-Id</code>，在查询审计中筛选以定位问题。</p>
  </section>
  <section class="panel">
    <h2>可复制示例</h2>
    <details
      v-for="(example, index) in examples"
      :key="example.title"
      :open="index === 0"
      class="advanced"
    >
      <summary>{{ example.title }}</summary>
      <NButton size="small" @click="copy(example.code)">复制</NButton>
      <pre>{{ example.code }}</pre>
    </details>
    <NAlert type="warning"
      >替换示例占位符。每个新业务请求生成新的幂等键，同一请求超时重试时复用原键。</NAlert
    >
  </section>
  <section class="panel">
    <h2>失败处理与重试边界</h2>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>HTTP</th>
            <th>错误码</th>
            <th>业务系统处理</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in errors" :key="e.code">
            <td>{{ e.status }}</td>
            <td>
              <code>{{ e.code }}</code>
            </td>
            <td>{{ e.handling }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
