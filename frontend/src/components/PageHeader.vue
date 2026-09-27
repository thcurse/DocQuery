<script setup lang="ts">
defineProps<{ title: string; description?: string; back?: string; backLabel?: string }>()
</script>
<template>
  <header class="page-heading">
    <div>
      <RouterLink v-if="back" :to="back" class="back-link">
        <svg class="action-icon" viewBox="0 0 24 24" aria-hidden="true">
          <path d="m12 5-7 7 7 7M5 12h14" />
        </svg>
        返回
      </RouterLink>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="actions"><slot /></div>
  </header>
</template>
