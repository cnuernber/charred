# Charred

Efficient character-based file parsing for csv and json formats.


* Zero dependencies.
* As fast as univocity or jackson.
* Same API as clojure.data.csv and clojure.data.json simply implemented far more
  efficiently.


* [API Documentation]()


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


See
