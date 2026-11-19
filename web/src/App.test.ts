import { createApp } from 'vue'
import { afterEach, expect, test } from 'vitest'

import App from './App.vue'
import router from './router'

afterEach(() => {
  document.body.replaceChildren()
})

test('the root route renders the study room home page', async () => {
  await router.push('/')
  await router.isReady()

  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(App)
  app.use(router)

  try {
    app.mount(root)
    expect(root.querySelector('h1')?.textContent).toBe('自习室预约系统')
  } finally {
    app.unmount()
  }
})
