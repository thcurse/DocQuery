<script setup lang="ts">
import { citationLocation } from '../domain/query'
import type { AnswerCitation } from '../domain/types'
defineProps<{ citations: AnswerCitation[]; kbId: number }>()
</script>
<template>
  <div v-if="!citations.length" class="state muted">回答中的引用来源会显示在这里。</div>
  <article v-for="c in citations" :key="`${c.citationIndex}-${c.blockId}`" class="citation-card">
    <div class="citation-number">{{ c.citationIndex }}</div>
    <RouterLink :to="`/knowledge-bases/${kbId}/documents/${c.documentId}`"
      ><strong>{{ c.documentName }}</strong></RouterLink
    ><small>v{{ c.versionNo }} · {{ citationLocation(c) }}</small>
    <p class="pre-wrap">{{ c.text }}</p>
    <small v-if="c.truncated">内容已截断</small>
  </article>
</template>
