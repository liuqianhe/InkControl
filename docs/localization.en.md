# Localization

[简体中文](localization.md)

The top-right 中 / EN control offers System, Simplified Chinese, and English. System is the default: Chinese systems use Chinese and other languages fall back to English. Preferences use device-protected storage. Switching recreates the Activity without requesting Root, changing enhancements, or modifying the process-wide Locale.

The launcher label and framework description use English `res/values` and Chinese `values-zh` resources, with supported languages declared in `locales_config.xml`. The existing code-based UI uses the UTF-8 phrase catalog `assets/i18n/en.tsv` for titles, feature descriptions, terms, options, status, dialogs, Toast messages, charts, and accessibility text. Longest phrases take precedence and formatted runtime numbers have explicit coverage. Raw monospace diagnostic output is preserved; actual media titles, lyrics, weather and calendar content retain their source language.

FeatureCatalog and Guide retain their stable routing and comparison values. Translation occurs at presentation boundaries and never changes IDs, properties, configuration, or shell protocols. Update the English catalog whenever UI wording changes. `tools/check_i18n.py` checks coverage, duplicates, and format placeholders. Cloud `TextCatalogTest` covers both languages, composed labels, temperature values, unknown content, and unchanged configuration.

Historical research and raw device evidence remain local in their original language. No automatic translation is applied to user content. Physical-device layout and interaction checks in both languages remain necessary before device acceptance.
