# Signing (keep keystores OFF GitHub)

## Never commit
- `*.keystore`, `*.jks`, passwords, `local.properties`

## GitHub Actions secrets (repo → Settings → Secrets)
1. `AEGIS_KEYSTORE_BASE64` — base64 of your keystore file  
   ```bash
   base64 -w0 aegis.keystore
   ```
2. `AEGIS_STORE_PASSWORD`
3. `AEGIS_KEY_PASSWORD`
4. `AEGIS_KEY_ALIAS` (usually `aegis`)

CI decodes the keystore at build time and deletes it after.

## Local build
```bash
export AEGIS_KEYSTORE_PATH=/path/to/aegis.keystore
export AEGIS_STORE_PASSWORD=...
export AEGIS_KEY_PASSWORD=...
export AEGIS_KEY_ALIAS=aegis
./gradlew assembleRelease
```

## If a keystore was already pushed
Treat it as **compromised**. Generate a **new** keystore for future releases.
