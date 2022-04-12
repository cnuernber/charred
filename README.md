# Charred

Efficient character-based file parsing for csv and json formats.


[![Clojars Project](https://clojars.org/com.cnuernber/charred/latest-version.svg)](https://clojars.org/com.cnuernber/charred)


* Zero dependencies.
* As fast as univocity or jackson.
* Same API as clojure.data.csv and clojure.data.json implemented far more
  efficiently.


* [API Documentation](https://cnuernber.github.io/charred/)


## Usage

```clojure
user> (require '[charred.api :as charred])
nil
user> (charred/read-json "{\"a\": 1, \"b\": 2}")
{"a" 1, "b" 2}
user> (charred/read-json "{\"a\": 1, \"b\": 2}" :key-fn keyword)
{:a 1, :b 2}
user> (println (charred/write-json-str *1))
{
  "a": 1,
  "b": 2
}
```


## Development

Before running a REPL you must compile the java files into target/classes.  This directory
will then be on your classpath.

```console
scripts/compile
```

Tests can be run with `scripts/run-tests` which will compile the java and then run the tests.


## License

MIT license.
