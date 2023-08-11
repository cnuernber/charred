(ns charred.bulk
  "Helpers for bulk operations such as concatenating a large sequence of csv files."
  (:require [charred.api :as charred])
  (:import [clojure.lang IReduceInit]))



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
   (let [user-tfn (get options :tfn identity)
         header? (get options :header? true)]
     (apply charred/write-csv
            output
            (reify IReduceInit
              (reduce [this rfn init]
                (let [first-output?* (volatile! true)
                      row-count (java.util.concurrent.atomic.AtomicLong.)]
                  (reduce (fn [acc input]
                            (let [s (charred/read-csv-supplier input options)
                                  tfn (if (and header? (not @first-output?*))
                                        (let [first?* (volatile! true)]
                                          (fn [row]
                                            (if @first?*
                                              (do
                                                (vreset! first?* false)
                                                nil)
                                              (user-tfn row))))
                                        user-tfn)]
                              (vreset! first-output?* false)
                              (reduce #(if-let [row (tfn %2)]
                                         (do
                                           (.incrementAndGet row-count)
                                           (rfn %1 row))
                                         %1)
                                      init s)))
                          init
                          fseq)
                  (.get row-count))))
            (apply concat (seq options))))))
