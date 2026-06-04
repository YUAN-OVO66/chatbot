<script setup lang="ts">
</script>

<template>
  <router-view />
</template>

<style>
:root {
  --color-primary: #7C3AED;
  --color-primary-light: #A78BFA;
  --color-primary-dark: #6D28D9;
  --color-cta: #06B6D4;
  --color-cta-hover: #0891B2;
  --color-bg: #FAF5FF;
  --color-surface: #FFFFFF;
  --color-text: #1E1B4B;
  --color-text-secondary: #6B7280;
  --color-border: #E5E7EB;
  --color-border-light: #F3F4F6;
  --font-family: 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.08);
  --shadow-lg: 0 8px 24px rgba(0, 0, 0, 0.12);
  --transition: 200ms ease;
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: var(--font-family);
  color: var(--color-text);
  background: var(--color-bg);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
</style>
