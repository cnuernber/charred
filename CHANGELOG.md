# Charred Changelog

## 1.002
 * Replaced `:log-level` with `:log-fn` to eliminate tools.logging dependency.
 * Tested rotating buffers vs. allocating and found rotation to be a bit faster.
 * Tested async? on large json buffer and found setting to true to be a bit faster.

## 1.001
 * Fixed two key issues found parsing wordnet.json - https://github.com/fluhus/wordnet-to-json/releases/download/v1.0/wordnet.json.gz

## 1.000
 * Initial release!!
