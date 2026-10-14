/**
 * Jest configuration for the AgriConnect backend.
 *
 * Deliberately `.cjs`: `package.json` declares `"type": "module"`, so a
 * `jest.config.js` would be treated as ESM and Jest's config loader would fail.
 * The test files themselves are native ESM and are run with
 * `node --experimental-vm-modules` (see the `test` script in package.json), so no
 * transform is needed.
 */
module.exports = {
  testEnvironment: 'node',
  transform: {},
  testMatch: ['<rootDir>/tests/**/*.test.js'],
  // Integration tests boot a real database connection and apply migrations.
  testTimeout: 30000,
  verbose: true,
};
