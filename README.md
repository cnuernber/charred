# Charred

Efficient character-based file parsing for csv and json formats.


[![Clojars Project](https://clojars.org/com.cnuernber/charred/latest-version.svg)](https://clojars.org/com.cnuernber/charred)

* Zero dependencies.
* As fast as univocity or jackson.
* Same API as clojure.data.csv and clojure.data.json implemented far more
  efficiently.


* [API Documentation](https://cnuernber.github.io/charred/)
* [Simple JSON benchmarks](https://github.com/cnuernber/fast-json)


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

#### A Note About Efficiency


If you are reading or writing a lot of small JSON objects the best option is to create a
[specialized parse fn](https://cnuernber.github.io/charred/charred.api.html#var-parse-json-fn)
to exactly the options that you need and pass in strings or char[] data.  A [similar pathway](https://cnuernber.github.io/charred/charred.api.html#var-write-json-fn) exists for high
performance writing of json objects.  The returned functions are safe to use in multithreaded
contexts.

The system is overall tuned for large files.  Small files or input streams should be setup with `:async?` false
and smaller `:bufsize` arguments such as 8192 as there is no gain for async loading when the file/stream is smaller than 1MB.
For smaller streams slurping into strings in an offline threadpool will lead to the highest performance.  For a particular
file size if you know you are going to parse many of these then you should gridsearch `:bufsize` and `:async?` as
that is a tuning pathway that I haven't put a ton of time into.  In general the system is tuned towards larger
files as that is when performance really does matter.

All the parsing systems have mutable options.  These can be somewhat faster and it is interesting to
look at the tradeoffs involved.  Parsing a csv using the raw supplier interface is a bit faster
than using the Clojure sequence pathway into persistent vectors and it probably doesn't really
change your consume pathway so it may be worth trying it.


## Development

Before running a REPL you must compile the java files into target/classes.  This directory
will then be on your classpath.

```console
scripts/compile
```

Tests can be run with `scripts/run-tests` which will compile the java and then run the tests.


## Lies, Damn Lies, and Benchmarks!

See the [fast-json project](https://github.com/cnuernber/fast-json/blob/master/src/fjson.clj#L247).  These times are for 
parsing a 100k json document using keywords for map keys - `:key-fn keyword`.

#### Intel JDK-19

| method       | performance   |
| ---          | ---:          |
| jsonista     | 531.239536 µs |
| charred      | 454.185163 µs |
| charred-hamf | 351.559837 µs |


#### Mac m-1 JDK-19

| method       | performance   |
| ---          | ---:          |
| jsonista     | 305.331 µs    |
| charred      | 271.501 µs    |
| charred-hamf | 210.597 µs    |


## License

MIT license.
