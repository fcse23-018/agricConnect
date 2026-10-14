/**
 * ESLint flat config (ESLint 9).
 *
 * Why a flat config and not `.eslintrc`: ESLint 9 removed the old config system,
 * and `package.json` has `"type": "module"`, so `eslint.config.js` is loaded as
 * ESM. Nothing is imported from `@eslint/js`/`globals` so `npm run lint` works even
 * though those packages are only transitive dependencies of ESLint.
 *
 * Scope: the service source and the tests, exactly matching `eslint src tests`.
 * Style is deliberately light — this config catches real defects (undefined
 * identifiers, accidental globals, unused bindings) without bikeshedding formatting.
 */

const NODE_GLOBALS = {
  process: 'readonly',
  console: 'readonly',
  Buffer: 'readonly',
  global: 'readonly',
  globalThis: 'readonly',
  setTimeout: 'readonly',
  clearTimeout: 'readonly',
  setInterval: 'readonly',
  clearInterval: 'readonly',
  setImmediate: 'readonly',
  URL: 'readonly',
  URLSearchParams: 'readonly',
  TextEncoder: 'readonly',
  TextDecoder: 'readonly',
  fetch: 'readonly',
  AbortController: 'readonly',
  structuredClone: 'readonly',
  __dirname: 'readonly',
  __filename: 'readonly',
  module: 'readonly',
  require: 'readonly',
  exports: 'writable',
};

const JEST_GLOBALS = {
  describe: 'readonly',
  it: 'readonly',
  test: 'readonly',
  expect: 'readonly',
  beforeAll: 'readonly',
  afterAll: 'readonly',
  beforeEach: 'readonly',
  afterEach: 'readonly',
  jest: 'readonly',
};

export default [
  {
    ignores: ['node_modules/**', 'coverage/**', 'dist/**', '*.min.js'],
  },
  {
    files: ['src/**/*.js', 'tests/**/*.js'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: NODE_GLOBALS,
    },
    linterOptions: {
      reportUnusedDisableDirectives: true,
    },
    rules: {
      // Correctness.
      'no-undef': 'error',
      'no-unused-vars': ['warn', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      }],
      'no-redeclare': 'error',
      'no-dupe-keys': 'error',
      'no-dupe-args': 'error',
      'no-func-assign': 'error',
      'no-unreachable': 'error',
      'no-constant-condition': ['error', { checkLoops: false }],
      'require-atomic-updates': 'warn',
      // Hygiene.
      'no-var': 'error',
      'prefer-const': ['warn', { destructuring: 'all' }],
      'eqeqeq': ['warn', 'smart'],
      'no-throw-literal': 'error',
      'object-shorthand': ['warn', 'properties'],
      // The service logs through `console` intentionally (structured request logs).
      'no-console': 'off',
    },
  },
  {
    files: ['tests/**/*.js'],
    languageOptions: {
      globals: { ...NODE_GLOBALS, ...JEST_GLOBALS },
    },
  },
];
