# Public repository and documentation maintenance

[简体中文](publication.md) · [Native Wiki](https://github.com/wzp100/InkControl/wiki/Home.en)

The user authorized publication after a sensitive-data review on 2026-10-18. Scope covered full Git history, ten Actions run logs, 28 artifacts, published candidates and nested APK/ZIP content, and the native Wiki export. Gitleaks v8.30.1 found no history credentials. All 15 generic-key alerts in logs were APK public-key fingerprints. Exact checks against the actual signing password, private-keystore fragments and cloud-input password found no exposure.

Historical commits contained a personal email. At the user's request, every author and committer now uses a GitHub noreply address, with identical source trees. The original repository remains a private historical archive, isolating old Release/Actions references. Sanitized history continues at `wzp100/InkControl`; complete backups and old-to-new commit mappings remain local. Historical private builds retain their original commit identities, which must not be confused with sanitized commit SHAs.

Public candidates continue from rc15 without replacing private rc14. The signing identity introduced at rc13 is unchanged. Vendor-input hashes are identical; their encryption password was refreshed. Public visibility grants no MIT rights to third-party binaries or artwork; see [NOTICE](../NOTICE.en.md). Private keys, passwords, personal backups, firmware images and raw device logs stay outside Git.

## Wiki source and publication

The native GitHub Wiki is the online reading surface. `docs/wiki` remains its reviewed source for offline clones and agent retrieval. Maintain both language versions, run `python tools/check_docs.py`, then:

```text
python tools/export_wiki.py --destination out/native-wiki-<unique-id>
git clone https://github.com/wzp100/InkControl.wiki.git out/wiki-checkout-<unique-id>
```

Copy the 24 exported Markdown files into the Wiki checkout, review the diff, commit with a GitHub noreply identity, and push its default branch. The exporter rewrites links and generates Home, a bilingual sidebar and a footer. A first Wiki requires saving Home on GitHub before cloning. Never copy the full source checkout, signing material, raw logs or access tokens into the Wiki. Bring any web edits back into the maintained source first.

All Java, native, resources and APK compilation and build tests remain on GitHub-hosted Actions. Documentation export and static checks can run locally. Cloud validation, public publication and device acceptance remain separate evidence levels; repository maintenance performs no phone operations.
