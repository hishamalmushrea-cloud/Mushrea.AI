# Signed release build — hand-back

Produced by the workflow run [37174670023](https://github.com/hishamalmushrea-cloud/Mushrea.AI/actions/runs/37174670023),
commit `e538eaf1b7b1968114bd84a2dedbb169771ab3d5`, on 2026-10-04T03:48:38Z.

| file | what it is |
|---|---|
| `mushrea-code-github-release.apk` | signed release APK, github flavour |
| `mushrea-code-fdroid-release.apk` | signed release APK, fdroid flavour |
| `release-cert.pem` | the public signing certificate |
| `signing-cert-sha256.txt` | its SHA-256 - the value `AllowedAPKSigningKeys` must carry |
| `identity.tar.aes` | the PKCS#12 keystore and its password, encrypted |
| `identity-aes-key.rsa` | the AES key, wrapped with RSA-OAEP-SHA256 to the requester |

certificate SHA-256: `7b935169e3997f9b742c9ad87189168ab89a7fde854d0af2a335d17ca1726669`

APK version: 1.2.26 (versionCode 65)

## What to do with this

1. Take `identity.tar.aes` + `identity-aes-key.rsa` and decrypt them with the private transport key:
   `openssl pkeyutl -decrypt -inkey <private>.pem -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 -in identity-aes-key.rsa -out aes.key`
   then `openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 -pass file:aes.key -in identity.tar.aes | tar -x`
2. Store the resulting keystore **outside any repository**, in two places, as if it were a password:
   Android only accepts an update signed by the same certificate, so losing it means every installed copy
   can never be updated.
3. To make future builds signed without this hand-back, set the four repository secrets from that keystore
   (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`)
   — see `docs/RELEASE.md` — or keep using this workflow, which needs no secrets at all.

This branch is a delivery envelope, not part of the product: nothing here is merged, tagged or released.
