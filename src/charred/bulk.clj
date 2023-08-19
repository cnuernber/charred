(ns charred.bulk
  "Helpers for bulk operations such as concatenating a large sequence of csv files."
  (:require [charred.api :as charred])
  (:import [java.util.concurrent.atomic AtomicLong]
           [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [java.util Iterator]
           [java.util.logging Logger Level]
           [clojure.lang IReduceInit Seqable ISeq]))


(defn cat-csv-inputs
  "Stateful transducer that, given a sequence of inputs, produces a single sequence
  of parsed csv rows.  This transducer slices off the header rows of downstream
  inputs when `:header?` is true.

  Options:
   - `:header?` - defaults to true - assume first row of each file is a header row.

  Options are passed through to read-csv-supplier.

  Example:

  ```clojure
  (transduce (comp (bulk/cat-csv-inputs options) (map tfn)) (charred/write-csv-rf options) fseq)
  ```"
  ([] (cat-csv-inputs nil))
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

(deftype ^:private TakeReducer [^Iterator src
                                ^:unsynchronized-mutable first-row
                                ^{:tag long} count
                                ^{:unsynchronized-mutable true
                                  :tag long} idx]
  Iterator
  (hasNext [this] (boolean (or (and (.hasNext src) (< idx count)) first-row)))
  (next [this] (let [rval (if first-row first-row (.next src))]
                 (if first-row
                   (set! first-row nil)
                   (set! idx (unchecked-inc idx)))
                 rval))

  IReduceInit
  (reduce [this rfn acc]
    (let [acc (if (and (not (reduced? acc)) first-row)
                (rfn acc first-row)
                acc)]
      (set! first-row nil)
      (loop [row-idx idx
             acc acc]
        (if (and (< row-idx count) (.hasNext src) (not (reduced? acc)))
          (recur (unchecked-inc row-idx) (rfn acc (.next src)))
          (do
            (set! idx row-idx)
            (if (reduced? acc)
              @acc
              acc)))))))

(defn- seq-batches
  [^Iterator iter ^java.lang.AutoCloseable closer]
  (if (.hasNext iter)
    (cons (vec (.next iter))
          (lazy-seq (seq-batches iter closer)))
    (do
      (.close closer)
      nil)))


(defn- abq->seq
  [^ArrayBlockingQueue abq ^long timeout ^java.lang.AutoCloseable closer]
  (let [v (.poll abq timeout TimeUnit/MILLISECONDS)]
    (if-not (identical? v ::finished)
      (cons v (lazy-seq (abq->seq abq timeout closer)))
      (do (.close closer) nil))))


(defn batch-csv-rows
  "Given a potentially very large sequence of rows, lazily return batches of rows.
  Returned object has an efficient iterator, IReduceInit (3 arg reduce) implementations
  and an efficient seq (safest) implementation.  **Each previous batch must be completely
  read before the .hasNext function of the iterator will return an accurate result.**


  If `:csv-load-thread-name` is provided then the loading will happen in a separate
  thread and an auto-closeable seq will be returned that is safe to use in pmap or similar
  operations.  This allows the CSV system to run at full speed while potentially cpu-intensive
  processing can happen in other threads.  Recommended batch sizes for this pathway are
  around 128000.  The loading can be stopped prematurely by closing the returned value
  *or* reading the entirety of the sequence.

  Options:

  * `:header?` - When true, the header row will be returned as the first row
    of each batch.  Defaults to true.
  * `:csv-load-thread-name` - When not nil, perform the csv parsing in a separate
     thread named `:csv-load-thread-name`.
  * `:csv-load-queue-depth` - Queue depth used for each batch of rows.  Defaults to 4.
  * `:csv-load-queue-timeout` - Timeout in milliseconds used for .put calls.  Defaults
    to 5 seconds.
  * `:csv-load-log-level` - Defaults to :info.  Options are `:debug` or `:info`.

  Examples:

```clojure
;; HANGS - each batch is not read entirely before .next call of iterator so iterator forever
;; has next.
user> (->> (charred/read-csv-supplier (java.io.File. \"stocks.csv\"))
           (bulk/batch-csv-rows 100)
           (vec))
;; Works - the seq implementation is always safe
user> (->> (charred/read-csv-supplier (java.io.File. \"stocks.csv\"))
           (bulk/batch-csv-rows 100)
           (seq)
           (vec))
;; Works fine -
user> (->> (charred/read-csv-supplier (java.io.File. \"stocks.csv\"))
           (bulk/batch-csv-rows 100)
           (map vec))

;; Very memory efficient -
user> (->> (charred/read-csv-supplier (java.io.File. \"stocks.csv\"))
           (bulk/batch-csv-rows 100)
           (reduce (fn [rc batch] (+ rc (count (vec batch))))
                   0))
;; Threaded example - not the batch is already a vector
user> (->> (charred/read-csv-supplier (java.io.File. \"stocks.csv\"))
           (bulk/batch-csv-rows 100 {:csv-load-thread-name \"CSV loader\" :csv-load-log-level :info})
           (reduce (fn [rc batch] (+ rc (count batch)))
                   0))
Aug 19, 2023 9:19:43 AM charred.bulk$batch_csv_rows$fn__6468 invoke
INFO: CSV load thread finished - 566 rows read
Aug 19, 2023 9:19:43 AM charred.bulk$batch_csv_rows$reify__6472 close
INFO: CSV load thread joined
566
```"
  ([batch-size row-seq] (batch-csv-rows batch-size nil row-seq))
  ([^long batch-size options row-seq]
   (let [header? (get options :header? true)
         row-iter (.iterator ^Iterable row-seq)
         header (when (and header? (.hasNext row-iter))
                  (.next row-iter))
         first-batch?* (volatile! true)
         batches (reify
                   Iterable
                   (iterator [this]
                     (reify Iterator
                       (hasNext [this] (or (and header @first-batch?*) (.hasNext row-iter)))
                       (next [this]
                         (vreset! first-batch?* false)
                         (TakeReducer. row-iter header batch-size 0))))
                   IReduceInit
                   (reduce [this rfn acc]
                     (let [iter (.iterator this)]
                       (loop [acc acc]
                         (if (and (not (reduced? acc))
                                  (.hasNext iter))
                           (recur (rfn acc (.next iter)))
                           (do
                             (when (instance? java.lang.AutoCloseable row-seq)
                               (.close ^java.lang.AutoCloseable row-seq))
                             (if (reduced? acc)
                               @acc
                               acc))))))
                   ;;This is necessary because the requirement that each batch be completely
                   ;;read before we know if there is a next batch.  So we use 'vec' to efficiently
                   ;;read each batch.
                   Seqable
                   (seq [this]
                     (seq-batches (.iterator this) this))
                   java.lang.AutoCloseable
                   (close [this]
                     (when (instance? java.lang.AutoCloseable row-seq)
                       (.close ^java.lang.AutoCloseable row-seq))))]
     (if-let [csv-thread-name (get options :csv-load-thread-name)]
       (let [queue-depth (get options :csv-load-queue-depth 4)
             queue (ArrayBlockingQueue. queue-depth)
             timeout (get options :csv-load-queue-timeout 5000)
             ^Level level (case (get options :csv-load-log-level :debug)
                            :debug Level/FINE
                            :info Level/INFO)
             except* (volatile! nil)
             continue?* (volatile! true)
             load-thread (Thread.
                          ^Runnable (fn []
                                      (try
                                        (let [rc
                                              (reduce (fn [rc batch]
                                                        (let [batch (vec batch)]
                                                          (if @continue?*
                                                            (do
                                                              (.offer queue batch timeout TimeUnit/MILLISECONDS)
                                                              (+ rc (count batch)))
                                                            (reduced rc))))
                                                      0
                                                      batches)]
                                          (if @continue?*
                                            (do
                                              (.offer queue ::finished timeout TimeUnit/MILLISECONDS)
                                              (-> (Logger/getGlobal)
                                                  (.log level (format "CSV load thread finished - %d rows read" rc))))
                                            (-> (Logger/getGlobal)
                                                (.log level (format "CSV load thread stopped - %d rows read" rc)))))
                                        (catch Exception e
                                          (vreset! except* e))))
                          csv-thread-name)
             closer* (delay
                       (vreset! continue?* false)
                       (.join load-thread)
                       (when (instance? java.lang.AutoCloseable row-seq)
                         (.close ^java.lang.AutoCloseable row-seq))
                       (-> (Logger/getGlobal)
                           (.log level "CSV load thread joined")))]
         (.start load-thread)
         (reify
           java.lang.AutoCloseable
           (close [this] @closer*)
           Seqable
           (seq [this] (abq->seq queue timeout this))
           Iterable
           (iterator [this] (.iterator ^Iterable (.seq this)))))
       batches))))


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
   (let [cat-tf (cat-csv-inputs options)
         write-rf (charred/write-csv-rf output options)]
     (if-let [tfn (get options :tfn)]
       (transduce (comp cat-tf (map tfn)) write-rf fseq)
       (transduce cat-tf write-rf fseq)))))


(comment

  ;;Interesting example of loading a zipped csv directly from disk without unzipping
  (do
    (import '[java.util.zip ZipFile ZipInputStream])
    (require '[clojure.java.io :as io])
    (defn load-zip
      [fname]
      (let [zf (ZipInputStream. (io/input-stream fname))
            fe (.getNextEntry zf)
            _ (println (format "Found %s" (.getName fe)))
            s (charred/read-csv-supplier zf)
            row-batches (batch-csv-rows 10000 s)]
        (reduce (fn [rc row-batch]
                  (+ rc (reduce (fn [rrc row]
                                  (+ rrc 1))
                                0
                                row-batch)))
                0
                row-batches))))
  )
