/**
 * prepack guard: `main`, `module`, `types` and `exports` point into lib/, so a
 * tarball without the build output would be unusable by anything that does
 * not read the TypeScript sources (Node tooling, TypeScript without the
 * "react-native" condition, web bundlers). Fail the pack instead of shipping it.
 */
const fs = require('fs');
const path = require('path');

const required = ['lib/module/index.js', 'lib/typescript/src/index.d.ts'];
const missing = required.filter(file => !fs.existsSync(path.join(__dirname, '..', file)));

if (missing.length > 0) {
  console.error(
    `react-native-call-reminder: build output missing (${missing.join(', ')}).\n` +
      'Install this package\'s devDependencies and run `yarn build` (see "Releasing" in README.md).',
  );
  process.exit(1);
}
