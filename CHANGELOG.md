# Charred Changelog
## 1.038
 * [json writers are now flushable](https://github.com/cnuernber/charred/issues/35) - thanks @hlship.
 
## 1.037
 * Fix mutable profile of the json supplier to support arbitrary key-fn and val-fn.
 
## 1.036
 * Fix fallout of JSON optimization - [issue 31](https://github.com/cnuernber/charred/issues/31)
 
## 1.035
 * Minor optimization of buffer sizes in async read test.
 * Major [JSON write optimization](https://github.com/cnuernber/charred/pull/30) - thanks @FieryCod
 
## 1.034
 * [CSV issue 26](https://github.com/cnuernber/charred/issues/26) - several comments in a row now parse correctly.
 
## 1.033 
 * blacklist and whitelist can now be done as blocklist and allowlist
 * read-json-supplier returns a something seqable and reducible so `(vec (read-json-supplier (io/file ...)))`  works like one thinks it would.

## 1.032
 * Added async pathway to bulk/batch-csv-rows method.  This is useful because
   parsing the csv can be cpu-intensive and this allows you to offload that work
   onto a separate thread but still get a simple sequence of batches.  See docs
   for [bulk/batch-csv-rows](https://cnuernber.github.io/charred/charred.bulk.html#var-batch-csv-rows).

## 1.031
 * Fix to bulk row-batch iterator.

## 1.030
 * transduce-compatible write-csv-rf pathway.
 * write-csv! no longer defaults close-writer! to true unless the writer is a string.

## 1.029
 * read-csv will no longer auto-close the reader unless :close-reader? true is explicitly provided.
 * bulk namespace for methods of dealing with large numbers of inputs.  Initial operation is
   concatenate-csv.

## 1.028
 * Fix for [issue 17](https://github.com/cnuernber/charred/issues/17) - make serialization of various datetime types
   consistent with other libraries.

## 1.027
 * Doc fix for json write api.
 * When writing json, any sequential thing will be interpreted as a json array.

## 1.026
 * Fast path for producing immutable datastructures with keyword keys.  If we go directly from the
   char buffer to the keyword, when possible, then we save about 25% of the time as we do not construct
   the intermediate string.  This is true whether you use ham-fisted's high performance hash maps or
   if you use clojure's immutable datastructures.

## 1.025
 * Backed out 024 optimizations.  This release is same as 1.023 - its about as good
   as we can get it.

## 1.024
 * JSON parser is again slightly optimized for jdk 17+.  This leads to equal or better timings
   across the json test suite in the cnuernber/fast-json.

## 1.023
 * Better docs and the ability to share the json string canonicalizer between parser
   invocations.
 * Faster small-json parsing.

## 1.022
 * The string canonicalizer was subtly dropping strings resulting in unneeded
   allocations.

## 1.021
 * Two optimizations for json parsing.  First, the parsing of lists and maps is inlined into
   main parseObject method.  Second, map keys are canonicalized leading to faster downstream
   processing especially for row-oriented datasets as java strings cache their hash codes.
   This also eliminated a potentially superfluous buffer copy in the case where the string
   data was described completely in the current parse buffer.

## 1.019
 * Fixed escaping - there was of course an off-by-one error when restarting after
   an escaped character.

## 1.018
 * Disabled the escape character by default - there are valid csv's that fail when the
   escape character is '\' - they have newlines in their quoted sections.

## 1.017
 * Making sure suppliers have a good iterator interface.

## 1.016
 * Support for backslash-escaped characters similar to bash-style text processing.

## 1.015
 * [issue 13](https://github.com/cnuernber/charred/issues/13) - make passing args more
   convenient.

## 1.014
 * Refactor fix to 11 to simplify unread uses.  Unread on eof now silently fails.

## 1.013
 * [issue 11](https://github.com/cnuernber/charred/issues/11) - Unread exception at end of valid file.
 * [issue 9](https://github.com/cnuernber/charred/issues/9) - Identifiable exceptions - charred now throws charred-specific exceptions.

## 1.012
 * [issue 8](https://github.com/cnuernber/charred/issues/8) - Quote not at beginning of line.

## 1.011
 * `:comment-char` is disabled for read-csv for backward compatibility with clojure.data.csv.

## 1.010
 * Fixed issue with comments in csv when comment begins field.

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
