'use strict'

// The runtime app has no Node dependencies: it uses Electron and Node built-ins only.
// Returning false prevents electron-builder from hoisting unrelated monorepo dependencies.
exports.default = async () => false
