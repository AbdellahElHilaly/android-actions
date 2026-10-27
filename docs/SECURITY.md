# Build security

This repository intentionally builds **debug** APKs with ephemeral GitHub-hosted runner keys, not production release-signed builds. The workflow grants `contents: read`, checks out source without persisting credentials, and only clones `https://github.com/owner/repo` paths. Gradle scripts are executable code; don't build untrusted repositories. The factory does not store signing keys, upload files to external servers, or accept arbitrary URL downloads.

HTML sites in the wrapper execute JavaScript. Network requests from that JavaScript can send data to external servers, and embedded third-party links may require internet. The offline packaging does not sanitize HTML or JavaScript. Avoid adding secrets to HTML files.

ZIP import enforces extraction paths, symlink/special-file rejection, uncompressed total limits, per-file size limits, and a file-count limit. This is a guardrail, not a substitute for reviewing source.
