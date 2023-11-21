(ns line-number-reader
  (:require [clojure.test :refer [deftest is]])
  (:import [charred LineNumberReader]
           [java.io StringReader PushbackReader]))


(deftest simple-newlines
  (let [rdr (LineNumberReader. (StringReader. "\ny\r\no\r") 3)]
    (is (== (.read rdr) 10))
    (is (== (.line rdr) 1))
    (is (== (.read rdr) (int \y)))
    (is (== (.column rdr) 1))
    (is (== (.read rdr) 10))
    (.unread rdr)
    (is (== (.column rdr) 1))
    (is (== (.line rdr) 1))
    (is (== (.read rdr) 10))
    (is (== (.line rdr) 2))
    (is (== (.read rdr) (int \o)))
    (.unread rdr)
    (is (== (.column rdr) 1))
    (is (== (.line rdr) 2))
    (is (== (.read rdr) 10))
    (is (== (.read rdr) -1))
    (is (== (.column rdr) 0))
    (is (== (.line rdr) 3))))


(comment
  (def test-data (apply str (flatten (repeat 2000 [\a \b \c \d \e \newline \f \return \newline]))))
  (defn read-all
    [r]
    (if (instance? java.io.PushbackReader r)
      (let [^PushbackReader r r]
        (loop [c (.read r)
               idx 0]
          (if-not (== -1 c)
            (recur (.read r) (unchecked-inc idx))
            idx)))
      (let [^LineNumberReader r r]
        (loop [c (.read r)
               idx 0]
          (if-not (== -1 c)
            (recur (.read r) (unchecked-inc idx))
            idx)))))

  (defn clojure-reader
    [^String data]
    (-> (StringReader. data)
        (java.io.LineNumberReader.)
        (PushbackReader.)))

  (dotimes [idx 10]
    (time (read-all (clojure-reader test-data))))

  (dotimes [idx 10]
    (time (read-all (LineNumberReader. (StringReader. test-data) 2056))))
  )
