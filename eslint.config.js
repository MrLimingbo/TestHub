import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['**/dist/**', '**/coverage/**', '**/node_modules/**', '**/target/**', '.testhub/**', 'release/**', 'artifacts/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{ts,vue}'],
    rules: {
      'no-undef': 'off',
    },
  },
  {
    files: ['services/api-runner/**/*.js', 'services/jmeter-runner/**/*.js', 'services/playwright-runner/**/*.{js,mjs}', 'services/appium-runner/**/*.js'],
    languageOptions: {
      globals: {
        AbortSignal: 'readonly',
        URL: 'readonly',
        clearInterval: 'readonly',
        console: 'readonly',
        process: 'readonly',
        setInterval: 'readonly',
        setTimeout: 'readonly',
      },
    },
  },
  {
    files: ['services/runner-transport/**/*.mjs'],
    languageOptions: { globals: { AbortController: 'readonly', AbortSignal: 'readonly', URL: 'readonly', fetch: 'readonly', process: 'readonly' } },
  },
  {
    files: ['services/testhub-cli/**/*.js'],
    languageOptions: {
      globals: {
        Buffer: 'readonly',
        process: 'readonly',
        Response: 'readonly',
        URL: 'readonly',
      },
    },
  },
  {
    files: ['apps/desktop/**/*.{cjs,js}', 'scripts/desktop/**/*.mjs'],
    languageOptions: {
      globals: {
        URL: 'readonly',
        __dirname: 'readonly',
        console: 'readonly',
        exports: 'readonly',
        module: 'readonly',
        process: 'readonly',
        require: 'readonly',
      },
    },
    rules: {
      '@typescript-eslint/no-require-imports': 'off',
    },
  },
  {
    files: ['apps/desktop/app/src/ui/**/*.js'],
    languageOptions: {
      globals: {
        document: 'readonly',
        setInterval: 'readonly',
        window: 'readonly',
      },
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/html-self-closing': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
    },
  },
)
