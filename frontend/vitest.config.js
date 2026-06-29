import { defineConfig } from 'vitest/config'

// Unit tests run in a plain Node environment — the logic under test (diagnostics → markers)
// is pure and needs neither a DOM nor a browser. Test globals are imported explicitly from
// 'vitest' in each spec, so we keep `globals` off to avoid leaking them into lint.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.{js,jsx}'],
  },
})
