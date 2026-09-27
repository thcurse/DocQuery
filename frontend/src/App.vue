<script setup lang="ts">
import { NConfigProvider, NMessageProvider, NDialogProvider, zhCN, dateZhCN } from 'naive-ui'
const theme = {
  common: {
    primaryColor: '#3564e4',
    primaryColorHover: '#4775ef',
    primaryColorPressed: '#254cbb',
    borderRadius: '8px',
    fontFamily: 'Inter, "Segoe UI", "Microsoft YaHei", sans-serif',
  },
}
</script>
<template>
  <NConfigProvider :locale="zhCN" :date-locale="dateZhCN" :theme-overrides="theme"
    ><NMessageProvider
      ><NDialogProvider><RouterView /></NDialogProvider></NMessageProvider
  ></NConfigProvider>
</template>
