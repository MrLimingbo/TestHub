'use strict'
const base = require('../package.json').build

// An external electron-builder config replaces package.build; retain every hook and resource.
module.exports = {
  ...base,
  mac: {
    ...base.mac,
    identity: '-',
    hardenedRuntime: false,
    notarize: false,
    // Re-signing embedded runtimes would invalidate their pinned byte inventory.
    signIgnore: ['/Contents/Resources/local-runtime/'],
    artifactName: 'TestHub-macOS-${arch}-preview.${ext}',
  },
}
