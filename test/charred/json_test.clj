(ns charred.json-test
  (:require [charred.api :as charred]
            [clojure.test :refer :all]
            [clojure.string :as str]))

(deftest read-from-pushback-reader
  (is (= 42 (charred/read-json "42"))))

(deftest read-from-reader
  (let [s (java.io.StringReader. "42")]
    (is (= 42 (charred/read-json s)))))

(deftest read-numbers
  (is (= 42 (charred/read-json "42")))
  (is (= -3 (charred/read-json "-3")))
  (is (= 3.14159 (charred/read-json "3.14159")))
  (is (= 6.022e23 (charred/read-json "6.022e23"))))

(deftest regression-31
  ;; [#31] Newlines no longer escaped in 1.035
  (is (= 1
         (->
          (charred/write-json-str
           {:some-key "new
                     lines
                     here"})
          str/split-lines
          count))))

(deftest read-bigint
  (is (= 123456789012345678901234567890N
         (charred/read-json "123456789012345678901234567890"))))


(deftest read-bigdec
  (is (= 3.14159M (charred/read-json "3.14159" :bigdec true))))

(deftest read-null
  (is (= nil (charred/read-json "null"))))

(deftest read-strings
  (is (= "Hello, World!" (charred/read-json "\"Hello, World!\""))))

(deftest escaped-slashes-in-strings
  (is (= "/foo/bar" (charred/read-json "\"\\/foo\\/bar\""))))

(deftest unicode-escapes
  (is (= " \u0beb " (charred/read-json "\" \\u0bEb \""))))

(deftest escaped-whitespace
  (is (= "foo\nbar" (charred/read-json "\"foo\\nbar\"")))
  (is (= "foo\rbar" (charred/read-json "\"foo\\rbar\"")))
  (is (= "foo\tbar" (charred/read-json "\"foo\\tbar\""))))

(deftest read-booleans
  (is (= true (charred/read-json "true")))
  (is (= false (charred/read-json "false"))))

(deftest ignore-whitespace
  (is (= nil (charred/read-json "\r\n   null"))))

(deftest read-arrays
  (is (= (vec (range 35))
         (charred/read-json "[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34]")))
  (is (= ["Ole" "Lena"] (charred/read-json "[\"Ole\", \r\n \"Lena\"]"))))

(deftest read-objects
  (is (= {:k1 1, :k2 2, :k3 3, :k4 4, :k5 5, :k6 6, :k7 7, :k8 8
          :k9 9, :k10 10, :k11 11, :k12 12, :k13 13, :k14 14, :k15 15, :k16 16}
         (charred/read-json "{\"k1\": 1, \"k2\": 2, \"k3\": 3, \"k4\": 4,
                          \"k5\": 5, \"k6\": 6, \"k7\": 7, \"k8\": 8,
                          \"k9\": 9, \"k10\": 10, \"k11\": 11, \"k12\": 12,
                          \"k13\": 13, \"k14\": 14, \"k15\": 15, \"k16\": 16}"
                        :key-fn keyword))))

(deftest read-nested-structures
  (is (= {:a [1 2 {:b [3 "four"]} 5.5]}
         (charred/read-json "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}"
                        :key-fn keyword))))

(deftest read-nested-structures-stream
  (is (= {:a [1 2 {:b [3 "four"]} 5.5]}
         (charred/read-json (java.io.StringReader. "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}")
                    :key-fn keyword))))

(deftest reads-long-string-correctly
  (let [long-string (str/join "" (take 100 (cycle "abcde")))]
    (is (= long-string (charred/read-json (str "\"" long-string "\""))))))

(deftest disallows-non-string-keys
  (is (thrown? Exception (charred/read-json "{26:\"z\""))))

(deftest disallows-barewords
  (is (thrown? Exception (charred/read-json "  foo  "))))

(deftest disallows-unclosed-arrays
  (is (thrown? Exception (charred/read-json "[1, 2,  "))))

(deftest disallows-unclosed-objects
  (is (thrown? Exception (charred/read-json "{\"a\":1,  "))))

(deftest disallows-empty-entry-in-object
  (is (thrown? Exception (charred/read-json "{\"a\":1,}")))
  (is (thrown? Exception (charred/read-json "{\"a\":1, }")))
  (is (thrown? Exception (charred/read-json "{\"a\":1,,,,}")))
  (is (thrown? Exception (charred/read-json "{\"a\":1,,\"b\":2}"))))

(deftest get-string-keys
  (is (= {"a" [1 2 {"b" [3 "four"]} 5.5]}
         (charred/read-json "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}"))))

(deftest keywordize-keys
  (is (= {:a [1 2 {:b [3 "four"]} 5.5]}
         (charred/read-json "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}"
                    :key-fn keyword))))

(deftest convert-values
  (is (= {:number 42 :date (java.sql.Date. 55 6 12)}
         (charred/read-json "{\"number\": 42, \"date\": \"1955-07-12\"}"
                    :key-fn keyword
                    :value-fn (fn [k v]
                                (if (= :date k)
                                  (java.sql.Date/valueOf v)
                                  v))))))

(deftest omit-values
  (is (= {:number 42}
         (charred/read-json "{\"number\": 42, \"date\": \"1955-07-12\"}"
                    :key-fn keyword
                    :value-fn (fn thisfn [k v]
                                (if (= :date k)
                                  :charred.api/elided
                                  v))))))

(declare pass1-string)

(deftest pass1-test
  (let [input (charred/read-json pass1-string)]
    (is (= "JSON Test Pattern pass1" (first input)))
    (is (= "array with 1 element" (get-in input [1 "object with 1 member" 0])))
    (is (= 1234567890 (get-in input [8 "integer"])))
    (is (= "rosebud" (last input)))))

; from http://www.json.org/JSON_checker/test/pass1.json
(def pass1-string
     "[
    \"JSON Test Pattern pass1\",
    {\"object with 1 member\":[\"array with 1 element\"]},
    {},
    [],
    -42,
    true,
    false,
    null,
    {
        \"integer\": 1234567890,
        \"real\": -9876.543210,
        \"e\": 0.123456789e-12,
        \"E\": 1.234567890E+34,
        \"\":  23456789012E66,
        \"zero\": 0,
        \"one\": 1,
        \"space\": \" \",
        \"quote\": \"\\\"\",
        \"backslash\": \"\\\\\",
        \"controls\": \"\\b\\f\\n\\r\\t\",
        \"slash\": \"/ & \\/\",
        \"alpha\": \"abcdefghijklmnopqrstuvwyz\",
        \"ALPHA\": \"ABCDEFGHIJKLMNOPQRSTUVWYZ\",
        \"digit\": \"0123456789\",
        \"0123456789\": \"digit\",
        \"special\": \"`1~!@#$%^&*()_+-={':[,]}|;.</>?\",
        \"hex\": \"\\u0123\\u4567\\u89AB\\uCDEF\\uabcd\\uef4A\",
        \"true\": true,
        \"false\": false,
        \"null\": null,
        \"array\":[  ],
        \"object\":{  },
        \"address\": \"50 St. James Street\",
        \"url\": \"http://www.JSON.org/\",
        \"comment\": \"// /* <!-- --\",
        \"# -- --> */\": \" \",
        \" s p a c e d \" :[1,2 , 3

,

4 , 5        ,          6           ,7        ],\"compact\":[1,2,3,4,5,6,7],
        \"jsontext\": \"{\\\"object with 1 member\\\":[\\\"array with 1 element\\\"]}\",
        \"quotes\": \"&#34; \\u0022 %22 0x22 034 &#x22;\",
        \"\\/\\\\\\\"\\uCAFE\\uBABE\\uAB98\\uFCDE\\ubcda\\uef4A\\b\\f\\n\\r\\t`1~!@#$%^&*()_+-=[]{}|;:',./<>?\"
: \"A key can be any string\"
    },
    0.5 ,98.6
,
99.44
,

1066,
1e1,
0.1e1,
1e-1,
1e00,2e+00,2e-00
,\"rosebud\"]")


(defn- double-value [_ v]
  (if (and (instance? Double v)
           (or (.isNaN ^Double v)
               (.isInfinite ^Double v)))
    (str v)
    v))

(deftest default-throws-on-eof
  (is (thrown? java.io.EOFException (charred/read-json ""))))

(deftest throws-eof-in-unterminated-array
  (is (thrown? java.io.EOFException
        (charred/read-json "[1, "))))

(deftest throws-eof-in-unterminated-string
  (is (thrown? java.io.EOFException
        (charred/read-json "\"missing end quote"))))

(deftest throws-eof-in-escaped-chars
  (is (thrown? java.io.EOFException
        (charred/read-json "\"\\"))))

(deftest accept-eof
  (is (= ::eof (charred/read-json "" :eof-error? false :eof-value ::eof))))

(deftest odd-buf-sizes
  (let [input (java.io.File. "test/data/json1k.json")
        js-data (charred/read-json input :key-fn keyword)]
    (is (= js-data (charred/read-json input :key-fn keyword :bufsize 7)))))


(deftest namespace-kwd
  (let [src-data #:a{:a 1, :b 2}]
    (is (= src-data (-> (charred/write-json-str src-data)
                        (charred/read-json :key-fn keyword))))))


(deftest packed-serialization
  (is (= "{\"a\":1,\"b\":2.3,\"c\":\"c1\"}" (charred/write-json-str {:a 1 :b 2.3 :c "c1"} :indent-str "")))
  (is (= "{\"a\":1,\"b\":2.3,\"c\":\"c1\"}" (charred/write-json-str {:a 1 :b 2.3 :c "c1"} :indent-str nil)))
  (is (= "{\"a\":1,\"b\":2.3,\"c\":\"c1\"}" (charred/write-json-str {:a 1 :b 2.3 :c "c1"})))
  (is (= "{\n  \"a\": 1,\n  \"b\": 2.3,\n  \"c\": \"c1\"\n}" (charred/write-json-str {:a 1 :b 2.3 :c "c1"} :indent-str "  "))))


(deftest serialize-sets
  (is (= "{\"id\":\"15\",\"vals\":[{\"id\":\"492\",\"views\":59},{\"id\":\"44\",\"views\":4}]}"
         (charred/write-json-str {:id "15" :vals #{{:id "44" :views 4} {:id "492" :views 59}}}))))

(deftest serialize-sql-date
  (is (= "\"2020-08-30T22:00:00Z\""
         (charred/write-json-str (java.sql.Date. 1598824800000)))))

(deftest serialize-instant
  (is (= "\"2020-08-30T22:00:00Z\""
         (charred/write-json-str #inst "2020-08-30T22:00:00.000-00:00")
         (charred/write-json-str (java.util.Date. 1598824800000)))))

(defn reader->str
  [^java.io.Reader rdr]
  (let [data (char-array 1024)
        n-read (.read rdr data 0 1024)]
    (String. data 0 n-read)))

(deftest json-multiple-read
  (let [src-data "{\"a\":1,\"b\":2.3,\"c\":\"c1\"}"
        json-data (str src-data src-data)
        jdata (charred/read-json-supplier (java.io.StringReader. json-data) {:key-fn keyword})]
    (is (= {:a 1 :b 2.3 :c "c1"} (.get jdata)))
    (is (= {:a 1 :b 2.3 :c "c1"} (.get jdata)))
    (is (thrown? java.io.EOFException (.get jdata)))))
