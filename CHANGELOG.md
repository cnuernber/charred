# Charred Changelog

## 1.009
 * `:comment-char` option is now supported and defaults to `#`.

## 1.008
 * [issue 5](https://github.com/cnuernber/charred/issues/5) - exception with csv ending with \r.
 * [issue 6](https://github.com/cnuernber/charred/issues/6) - Sets failed to serialize to json correctly.
 
## 1.007
 * Writing packed json is now the default as this matches clojure.data.json and cheshire.  To get the hold behavior back use `:indent-str "  "`.

## 1.006
 * [Issue 4](https://github.com/cnuernber/charred/issues/4) - Setting indent-str to nil really should mean no whitespace.

## 1.005
 * [Issue 3](https://github.com/cnuernber/charred/issues/3) - Namespaced keywords are written out with their namespaces.

## 1.004
 * Integer column indexes can be used in whitelists and blacklists.

## 1.003
 * CSV parse option `:nil-empty-values?` defaults to false to match clojure.data.csv.
 * The supplier returned from read-csv-supplier now implements IReduce and Seqable.

## 1.002
 * Replaced `:log-level` with `:log-fn` to eliminate tools.logging dependency.
 * Tested rotating buffers vs. allocating and found rotation to be a bit faster.
 * Tested async? on large json buffer and found setting to true to be a bit faster.

## 1.001
 * Fixed two key issues found parsing wordnet.json - https://github.com/fluhus/wordnet-to-json/releases/download/v1.0/wordnet.json.gz

## 1.000
 * Initial release!!
