(ns charred.bulk
  "Helpers for bulk operations such as concatenating a large sequence of csv files."
  (:require [charred.api :as charred])
  (:import [clojure.lang IReduceInit]))


(defn cat-csv-rows
  "Stateful transducer that, given an input, reduces over the csv rows.

  Options:
   - `:header?` - defaults to true - assume first row of each file is a reader row.

  Options are passed through to read-csv-supplier."
  ([] (cat-csv-rows nil))
  ([options]
   (let [header? (get options :header? true)]
     (fn [row-rf]
       (let [first?* (volatile! true)]
         (fn
           ([] (row-rf))
           ([acc] (row-rf acc))
           ([acc input]
            (let [s (charred/read-csv-supplier input options)
                  first? @first?*]
              (when first? (vreset! first?* false))
              (reduce row-rf acc
                      (if (and header? (not first?))
                        (do (.get s) s)
                        s))))))))))


(defn concatenate-csv
  "Given a sequence of csv files, concatenate into a single csv file.
  * fseq - a sequence of java.io.File's or other inputs to read-csv-supplier
  * output - an output stream or other closeable stream.


  Returns the number of rows written.

  Options:
   - `:header?` - defaults to true - assume first row of each file is a reader row.
   - `:tfn` - function from row->row that receives all output rows (header rows, aside from the first
      are elided).  If this function returns 'nil' that row is then elided from output.

  Example:

```clojure
user> (->> (repeat 10 (java.io.File. \"/home/chrisn/dev/tech.all/tech.ml.dataset/test/data/stocks.csv\"))
           (bulk/concatenate-csv \"test/data/big-stocks.csv\" {:header? false}))
5610
user> (->> (repeat 10 (java.io.File. \"/home/chrisn/dev/tech.all/tech.ml.dataset/test/data/stocks.csv\"))
           (bulk/concatenate-csv \"test/data/big-stocks.csv\" {:header? true}))
5601
```
  "
  ([output fseq] (concatenate-csv output nil fseq))
  ([output options fseq]
   ;; The plan here is to provide write-csv an implementation of IReduceInit that does the
   ;; concatenation and transformation of the data inline with the reduce call.
   (let [cat-tf (cat-csv-rows options)
         write-rf (charred/write-csv-rf output options)]
     (if-let [tfn (get options :tfn)]
       (transduce (comp cat-tf (map tfn)) write-rf fseq)
       (transduce cat-tf write-rf fseq)))))
