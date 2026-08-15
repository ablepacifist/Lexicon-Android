import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.alexdyakin.lexicon',
  appName: 'Lexicon',
  // Populated by scripts/build.ps1 from the Lexicon and mumble-bridge builds.
  // Not checked in — see .gitignore.
  webDir: 'www',
  android: {
    // Serve the bundled build over https://localhost so the WebView treats it as
    // a secure context — getUserMedia (voice chat) refuses to run otherwise.
    // Both this and capacitor://localhost are allowlisted in LexiconSecurityConfig.
    allowMixedContent: false,
  },
  server: {
    androidScheme: 'https',
  },
};

export default config;
