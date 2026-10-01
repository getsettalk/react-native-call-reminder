// Used when the library is developed/tested on its own. Inside a host app the
// app's own (root) babel config applies — nested babel.config.js files are
// ignored by Babel, so this cannot interfere with it.
module.exports = {
  presets: ['module:@react-native/babel-preset'],
};
