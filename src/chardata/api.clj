(ns chardata.api
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [chardata.coerce :as coerce]
            [clojure.set :as set])
  (:import [chardata CharBuffer CharReader CSVReader CSVReader$RowReader JSONReader
            JSONReader$ObjReader CloseableSupplier CSVWriter JSONWriter]
           [java.util.concurrent ArrayBlockingQueue Executors ExecutorService ThreadFactory]
           [java.lang AutoCloseable]
           [java.util.function Supplier LongPredicate BiConsumer]
           [java.util Arrays Iterator NoSuchElementException BitSet List Map Map$Entry]
           [java.io Reader StringReader Writer StringWriter]
           [clojure.lang MapEntry Keyword Symbol]
           [java.sql Date]
           [java.time Instant]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defonce ^:private default-thread-pool*
  (delay
    (Executors/newCachedThreadPool
     (reify ThreadFactory
       (newThread [this runnable]
         (let [t (Thread. runnable (str (ns-name *ns*)))]
           (.setDaemon t true)
           t))))))


(defn default-executor-service
  "Default executor service that is created via 'newCachedThreadPool with a custom thread
  factory that creates daemon threads.  This is an executor service that is suitable for
  blocking operations as it creates new threads as needed."
  ^ExecutorService[]
  @default-thread-pool*)


(deftype ^:private QueueException [e])


(deftype ^:private QueueFn [^{:unsynchronized-mutable true
                              :tag ArrayBlockingQueue} queue
                            close-fn*]
  CloseableSupplier
  (get [this]
    (when queue
      (let [value (.take queue)]
        (cond
          (identical? value ::end)
          (do
            (.close this)
            nil)
          (instance? QueueException value)
          (do
            (.close this)
            (throw (.e ^QueueException value)))
          :else
          value))))
  (close [this]
    (set! queue nil)
    @close-fn*))


(defn queue-supplier
  "Given a supplier or clojure fn, create a new thread that will read that
  fn and place the results into a queue of a fixed size.  Returns new suplier.
  Iteration stops when the src-fn returns nil.

  Options:

  * `:queue-depth` - Queue depth.  Defaults to 16.
  * `:log-level` - When set a message is logged when the iteration is finished.
  * `:executor-service` - Which executor service to use to run the thread.  Defaults to
     a default one created via [[default-executor-service]].
  * `:close-fn` - Function to call to close upstream iteration."
  [src-fn & [options]]
  (let [queue-depth (long (get options :queue-depth 16))
        queue (ArrayBlockingQueue. queue-depth)
        continue?* (volatile! true)
        close-fn (get options :close-fn)
        src-fn (coerce/->supplier src-fn)
        run-fn (fn []
                 (try
                   (loop [thread-continue? @continue?*
                          next-val (.get src-fn)]
                     (if (and thread-continue? next-val)
                       (do
                         (.put queue next-val)
                         (recur @continue?* (.get src-fn)))
                       (.put queue ::end)))
                   (catch Exception e
                     (.put queue (QueueException. e)))))
        ^ExecutorService service (or (get options :executor-service)
                                     (default-executor-service))
        close-fn* (delay
                    (try
                      (vreset! continue?* false)
                      (.clear queue)
                      (when close-fn
                        (close-fn))
                      (when-let [ll (get options :log-level)]
                        (log/log ll "queue-fn thread shutdown"))
                      (catch Exception e
                        (log/warnf e "Error closing down queue-fn thread"))))]
    (.submit service ^Callable run-fn)
    (QueueFn. queue close-fn*)))

(defn- ->reader
  ^Reader [item]
  (cond
    (instance? Reader item)
    item
    (string? item)
    (StringReader. item)
    :else
    (io/reader item)))



(deftype RotatingCharBufFn [^{:unsynchronized-mutable true
                              :tag Reader} reader
                            ^{:unsynchronized-mutable true
                              :tag long} cur-buf-idx
                            ^objects buffers
                            close-reader?]
  CloseableSupplier
  (get [this]
    (when reader
      (let [^chars next-buf (aget buffers (rem cur-buf-idx
                                               (alength buffers)))
            nchars (.read reader next-buf)]
        (set! cur-buf-idx (unchecked-inc cur-buf-idx))
        (cond
          (== nchars (alength next-buf))
          next-buf
          (== nchars -1)
          (do
            (.close this)
            nil)
          :else
          (Arrays/copyOf next-buf nchars)))))
  (close [this]
    (when (and reader close-reader?)
      (.close reader))
    (set! reader nil)))


(deftype AllocCharBufFn [^{:unsynchronized-mutable true
                           :tag Reader} reader
                         ^long bufsize
                         close-reader?]
  CloseableSupplier
  (get [this]
    (when reader
      (let [^chars next-buf (char-array bufsize)
            nchars (.read reader next-buf)]
        (cond
          (== nchars (alength next-buf))
          next-buf
          (== nchars -1)
          (do
            (.close this)
            nil)
          :else
          (Arrays/copyOf next-buf nchars)))))
  (close [this]
    (when (and reader close-reader?)
      (.close reader))
    (set! reader nil)))


(defn reader->char-buf-supplier
  "Given a reader, return a supplierthat when called reads the next buffer of the reader.
  When n-buffers is >= 0, this function iterates through a fixed number of buffers under
  the covers so you need to be cognizant of the number of actual buffers that you want to
  have present in memory. This fn also implement `AutoCloseable` and closing it will close
  the underlying reader.

  Options:

  * `:n-buffers` - Number of buffers to use.  Defaults to -1 - if this number is positive
  but too small then buffers in flight will get overwritten.  If n-buffers is <= 0 then
  buffers are allocated as needed and not reused - this is the safest option.
  * `:bufsize` - Size of each buffer - defaults to (* 64 1024).  Small improvements are
  sometimes seen with larger or smaller buffers.
  * `:async?` - defaults to false.  When true data is read in an async thread.
  * `:close-reader?` - When true, close input reader when finished.  Defaults to true."
  ^CloseableSupplier [rdr & [options]]
  (let [rdr (->reader rdr)
        async? (and (> (.availableProcessors (Runtime/getRuntime)) 1)
                    (get options :async? false))
        options (if async?
                  ;;You need some number more buffers than queue depth else buffers will be
                  ;;overwritten during processing.  I calculate you need at least 2  - one
                  ;;in the source thread, and one that the system is parsing.
                  (let [qd (long (get options :queue-depth 4))]
                    (assoc options :queue-depth qd
                           :async? true
                           :n-buffers (get options :n-buffers -1)
                           :bufsize (get options :bufsize (* 64 1024))))
                  (assoc options :async? false))
        n-buffers (long (get options :n-buffers -1))
        bufsize (long (get options :bufsize (* 64 1024)))
        src-fn (if (> n-buffers 0)
                 (let [buffers (object-array (repeatedly n-buffers #(char-array bufsize)))]
                   (RotatingCharBufFn.  rdr 0 buffers (get options :close-reader? true)))
                 (AllocCharBufFn. rdr bufsize (get options :close-reader? true)))]
    (if async?
      (queue-supplier src-fn options)
      src-fn)))


(defonce char-ary-cls (type (char-array 0)))


(defn reader->char-reader
  "Given a reader, return a CharReader which presents some of the same interface
  as a pushbackreader but is only capable of pushing back 1 character.  It is extremely
  quick to instantiate this object from a string or character array.

  Options:

  Options are passed through mainly unchanged to queue-iter and to
  [[reader->char-buf-iter]].

  * `:async?` - default to true - reads the reader in an offline thread into character
     buffers."
  (^CharReader [rdr options]
   (cond
     (string? rdr)
     (CharReader. ^String rdr)
     (instance? char-ary-cls rdr)
     (CharReader. ^chars rdr)
     :else
     (let [async? (and (> (.availableProcessors (Runtime/getRuntime)) 1)
                       (get options :async? true))
           options (if async? (assoc options :async? true) options)]
       (CharReader. ^Supplier (reader->char-buf-supplier rdr options)))))
  (^CharReader [rdr]
   (cond
     (string? rdr)
     (CharReader. ^String rdr)
     (instance? char-ary-cls rdr)
     (CharReader. ^chars rdr)
     :else
     (CharReader. ^Supplier (reader->char-buf-supplier rdr nil)))))


(def ^{:private true
       :tag LongPredicate}
  true-unary-predicate
  (reify LongPredicate
    (test [this arg] true)))


(deftype ^:private CSVRowSupplier [^{:unsynchronized-mutable true
                                     :tag CSVReader$RowReader} rdr
                                   ^{:unsynchronized-mutable true} first-row
                                   close-fn*]
  CloseableSupplier
  (get [this]
    (if first-row
      (do
        (let [fr first-row]
          (set! first-row nil)
          fr))
      (when rdr
        (let [retval (.nextRow rdr)]
          (when-not retval
            (.close this))
          retval))))
  (close [this]
    (set! first-row nil)
    (set! rdr nil)
    @close-fn*))


(defn- ->character
  [v]
  (cond
    (char? v)
    v
    (string? v)
    (do
      (when-not (== 1 (.length ^String v))
        (throw (Exception.
                (format "Only single character separators allowed: - \"%s\""
                        v))))
      (first v))
    (number? v)
    (unchecked-char v)))


(defn read-csv-supplier
  "Read a csv into a row supplier.  Parse algorithm the same as clojure.data.csv although
  this returns an iterator and each row is an ArrayList as opposed to a persistent
  vector.  To convert a java.util.List into something with the same equal and hash semantics
  of a persistent vector use either `tech.v3.datatype.ListPersistentVector` or `vec`.  To
  convert an iterator to a sequence use iterator-seq.

  The iterator returned derives from AutoCloseable and it will terminate the iteration and
  close the underlying iterator (and join the async thread) if (.close iter) is called.

  For a drop-in but much faster replacement to clojure.data.csv use [[read-csv-compat]].

  Options:

  * `:async?` - Defaults to true - read the file into buffers in an offline thread.  This
     speeds up reading larger files (1MB+) by about 30%.
  * `:separator` - Field separator - defaults to \\,.
  * `:quote` - Quote specifier - defaults to //\".
  * `:close-reader?` - Close the reader when iteration is finished - defaults to true.
  * `:column-whitelist` - Sequence of allowed column names.
  * `:column-blacklist` - Sequence of dis-allowed column names.  When conflicts with
     `:column-whitelist` then `:column-whitelist` wins.
  * `:trim-leading-whitespace?` - When true, leading spaces and tabs are ignored.  Defaults to true.
  * `:trim-trailing-whitespace?` - When true, trailing spaces and tabs are ignored.  Defaults
     to true
  * `:nil-empty-values?` - When true, empty strings are elided entirely and returned as nil
     values. Defaults to true."
  ^CloseableSupplier [input & [options]]
  (let [rdr (reader->char-reader input options)
        sb (CharBuffer. (get options :trim-leading-whitespace? true)
                        (get options :trim-trailing-whitespace? true)
                        (get options :nil-empty-values? true))
        nil-empty? (get options :nil-empty-values? true)
        quote (->character (get options :quote \"))
        separator (->character (get options :separator \,))
        row-reader (CSVReader$RowReader. rdr sb true-unary-predicate quote separator)
        ;;mutably changes row in place
        next-row (.nextRow row-reader)
        ^BitSet column-whitelist
        (when (or (contains? options :column-whitelist)
                  (contains? options :column-blacklist))
          (let [whitelist (when-let [data (get options :column-whitelist)]
                            (set data))
                blacklist (when-let [data (get options :column-blacklist)]
                            (set/difference (set data) (or whitelist #{})))
                indexes
                (->> next-row
                     (map-indexed
                      (fn [col-idx cname]
                        (when (or (and whitelist (whitelist cname))
                                  (and blacklist (not (blacklist cname))))
                          col-idx)))
                     (remove nil?)
                     (seq))
                bmp (BitSet.)]
            (doseq [idx indexes]
              (.set bmp (unchecked-int idx)))
            bmp))
        ^LongPredicate col-pred (if column-whitelist
                                   (reify LongPredicate
                                     (test [this arg]
                                       (.get column-whitelist (unchecked-int arg))))
                                   true-unary-predicate)
        close-fn* (delay
                    (when (get options :close-reader? true)
                      (.close rdr)))]
    (when (and next-row column-whitelist)
      (let [^List cur-row next-row
            nr (.size cur-row)
            dnr (dec nr)]
        (dotimes [idx nr]
          (let [cur-idx (- dnr idx)]
            (when-not (.get column-whitelist cur-idx)
              (.remove cur-row (unchecked-int cur-idx)))))))
    (.setPredicate row-reader col-pred)
    (if next-row
      (CSVRowSupplier. row-reader next-row close-fn*)
      (do
        @close-fn*
        (reify CloseableSupplier
          (get [this] nil)
          (close [this]))))))


;;Count the things returned from a supplier
(defn ^:no-doc supplier-count
  ^long [data]
  (let [data (coerce/->supplier data)]
    (loop [rc 0]
      (if (.get data)
        (recur (unchecked-inc rc))
        rc))))


(defn read-csv
  "Read a csv returning a clojure.data.csv-compatible sequence.  For options
  see [[read-csv-supplier]]."
  [input & options]
  (let [options (->> (partition 2 options)
                     (map vec)
                     (into {}))]
    (->> (read-csv-supplier input options)
         (coerce/supplier->seq)
         (map vec))))


(defn write-csv
  "Writes data to writer in CSV-format.
   Options:

     * `:separator` - Default \\,)
     * `:quote` - Default \\\")
     * `:quote?` A predicate function which determines if a string should be quoted.
        Defaults to quoting only when necessary.  May also be the the value 'true' in which
        case every field is quoted.
     :newline (:lf (default) or :cr+lf)
     :close-writer? - defaults to true.  When true, close writer when finished."
  ([w data & options]
   (let [options (if-not (empty? options)
                   (apply hash-map options)
                   {})
         ^String line-end (case (get options :newline :lf)
                            :lf "\n"
                            :cr "\r"
                            :cr+lf "\r\n")
         close-writer? (get options :close-writer? true)
         quote (unchecked-char (->character (get options :quote \")))
         sep (unchecked-char (->character (get options :separator \,)))
         quote?-arg (get options :quote?)
         quote-pred (coerce/->predicate
                     (cond
                       (nil? quote?-arg)
                       (CSVWriter/makeQuotePredicate sep quote)
                       (= quote?-arg true)
                       CSVWriter/truePredicate
                       :else
                       (coerce/->predicate quote?-arg)))
         cb (CharBuffer.)
         sep (unchecked-int sep)
         w (io/writer w)]
     (try
       (coerce/doiter
        row data
        (let [first?* (volatile! true)]
          (coerce/doiter
           field row
           (let [field (str field)]
             (if-not @first?*
               (.write w sep)
               (vreset! first?* false))
             (if (.test quote-pred field)
               (do
                 (CSVWriter/quote field quote cb)
                 (.write w (.buffer cb) 0 (.length cb)))
               (.write w field)))))
        (.write w line-end))
       (finally
         (when close-writer?
           (.close w)))))))


(defn json-reader-fn
  "Given options, return a function that when called constructs a json reader from
  exactly those options.  This avoids the work of upacking/analyzing the options
  when constructing many json readers for a sequence small inputs."
  [options]
  (let [eof-error? (get options :eof-error? true)
        eof-value (get options :eof-value :eof)
        [key-fn val-fn] [(get options :key-fn) (get options :value-fn)]
        [obj-iface-default array-iface-default]
        (case (get options :profile :immutable)
          :mutable
          [JSONReader/mutableObjReader JSONReader/mutableArrayReader]
          :raw
          [JSONReader/rawObjReader JSONReader/mutableArrayReader]
          :immutable
          (if (or key-fn val-fn)
            (let [key-fn (or key-fn identity)
                  val-fn (or val-fn (fn val-identity [k v] v))]
              [(reify JSONReader$ObjReader
                 (newObj [this] (transient {}))
                 (onKV [this obj k v]
                   (let [k (key-fn k)
                         v (val-fn k v)]
                     (if-not (identical? v ::elided)
                       (assoc! obj k v)
                       obj)))
                 (finalizeObj [this obj]
                   (persistent! obj)))
               JSONReader/immutableArrayReader])
            [nil nil]))
        obj-iface (get options :obj-iface obj-iface-default)
        array-iface (get options :array-iface array-iface-default)
        bigdec-fn (coerce/->function (if (get options :bigdec)
                                       #(BigDecimal. ^String %)
                                       (get options :double-fn)))
        eof-fn (coerce/->supplier
                (get options :eof-fn
                     #(if eof-error?
                        (throw (java.io.EOFException. "Unexpected end of input"))
                        eof-value)))]
    #(JSONReader. bigdec-fn
                  array-iface
                  obj-iface
                  eof-fn)))



(deftype ^:private JSONSupplier [^{:unsynchronized-mutable true
                                   :tag JSONReader} rdr
                                 close-fn*]
  CloseableSupplier
  (get [this]
    (when rdr
      (let [nextobj (.readObject rdr)]
        (when-not nextobj
          (.close this))
        nextobj)))
  (close [this]
    (set! rdr nil)
    @close-fn*))


(defn read-json-supplier
  "Read one or more JSON objects.
  Returns an auto-closeable supplier that when called by default throws an exception
  if the read pathway is finished.  Input may be a character array or string (most efficient)
  or something convertible to a reader.  Options for conversion to reader are described in
  [[reader->char-reader]] although for the json case we default `:async?` to false as
  most json is just too small to benefit from async reading of the input.

  Options:

  * `:bigdec` - When true use bigdecimals for floating point numbers.  Defaults to false.
  * `:double-fn` - If :bigdec isn't provided, use this function to parse double values.
  * `:profile` - Which performance profile to use.  This simply provides defaults to
     `:array-iface` and `:obj-iface`. The default `:immutable` value produces persistent datastructures and supports value-fn and key-fn.
     `:mutable` produces an object arrays and java.util.HashMaps - this is about
     30% faster. `:raw` produces ArrayLists for arrays and a
     JSONReader$JSONObj type with a public data member that is an ArrayList for objects.
  * `:key-fn` - Function called on each string map key.
  * `:value-fn` - Function called on each map value.  Function is passed the key and val so it
     takes 2 arguments.  If this function returns `:tech.v3.datatype.char-input/elided` then
     the key-val pair will be elided from the result.
  * `:array-iface` - Implementation of JSONReader$ArrayReader called on the object array of values for a javascript array.
  * `:obj-iface` - Implementation of JSONReader$ObjReader called for each javascript
    object.  Note that providing this overrides key-fn and value-fn.
  * `:eof-error?` - Defaults to true - when eof is encountered when attempting to read an
     object throw an EOF error.  Else returns a special EOF value.
  * `:eof-value` - EOF value.  Defaults to
  * `:eof-fn` - Function called if readObject is going to return EOF.  Defaults to throwing an
     EOFException."
  ^CloseableSupplier [input & [options]]
  (let [^JSONReader json-rdr ((json-reader-fn options))
        close-fn* (delay (.close json-rdr))
        ;;default async? to false
        options (assoc options :async? (get options :async? false))]
    (.beginParse json-rdr (reader->char-reader input options))
    (JSONSupplier. json-rdr close-fn*)))


(defn read-json
  "Drop in replacement for clojure.data.json/read and clojure.data.json/read-str.  For options
  see [[read-json-supplier]]."
  [input & args]
  (with-open [json-fn (read-json-supplier input (into {} (map vec (partition 2 args))))]
    (.get json-fn)))


(defn parse-json-fn
  "Return a function from input->json.  Reuses the parse context and thus when
  parsing many small JSON inputs where you intend to get one and only one JSON
  object from them this pathway is a bit more efficient than read-json.

  Same options as [[read-json-supplier]]."
  [& [options]]
  (let [json-rdr-fn (json-reader-fn options)
        ;;default async to false
        options (assoc options :async? (get options :async? false))]
    (fn [input]
      (let [^JSONReader json-rdr (json-rdr-fn)]
        (with-open [rdr (reader->char-reader input options)]
          (.beginParse json-rdr rdr)
          (.readObject json-rdr))))))


(defprotocol PToJSON
  (->json-data [item]
    "Automatic conversion of some subset of types to something acceptible to json.
Defaults to toString for types that aren't representable in json."))


(extend-protocol PToJSON
  Object
  (->json-data [item]
    ;;Default to convert sql date to instant.
    (if (instance? java.sql.Date item)
      (-> (.getTime ^java.sql.Date item)
          (Instant/ofEpochMilli)
          (.toString))
      (.toString ^Object item)))
  Number
  (->json-data [item] item)
  String
  (->json-data [item] item)
  List
  (->json-data [item] item)
  Map
  (->json-data [item] item)
  Keyword
  (->json-data [item] (name item))
  Symbol
  (->json-data [item] (name item)))


(defmacro define-array-iter
  [name ary-type]
  `(do
     (deftype ~name [~(with-meta (symbol "ary") {:tag ary-type})
                     ~(with-meta (symbol "idx") {:unsynchronized-mutable true
                                                 :tag 'long})
                     ~(with-meta (symbol "alen") {:tag 'long})]
       Iterator
       (hasNext [this] (< ~'idx ~'alen))
       (next [this] (let [retval# (aget ~'ary ~'idx)]
                      (set! ~'idx (unchecked-inc ~'idx))
                      retval#)))
     (def ~(with-meta (symbol (str name "-ary-type"))
             {:private true
              :tag 'Class}) ~(Class/forName ary-type))))


(define-array-iter ByteArrayIter "[B")
(define-array-iter ShortArrayIter "[S")
(define-array-iter CharArrayIter "[C")
(define-array-iter IntArrayIter "[I")
(define-array-iter LongArrayIter "[J")
(define-array-iter FloatArrayIter "[F")
(define-array-iter DoubleArrayIter "[D")
(define-array-iter ObjectArrayIter "[Ljava.lang.Object;")


(defn- ary-iter
  ^Iterator [ary-data]
  (cond
    (instance? ByteArrayIter-ary-type ary-data)
    (ByteArrayIter. ary-data 0 (alength ^bytes ary-data))
    (instance? ShortArrayIter-ary-type ary-data)
    (ShortArrayIter. ary-data 0 (alength ^shorts ary-data))
    (instance? CharArrayIter-ary-type ary-data)
    (CharArrayIter. ary-data 0 (alength ^chars ary-data))
    (instance? IntArrayIter-ary-type ary-data)
    (IntArrayIter. ary-data 0 (alength ^ints ary-data))
    (instance? LongArrayIter-ary-type ary-data)
    (LongArrayIter. ary-data 0 (alength ^longs ary-data))
    (instance? FloatArrayIter-ary-type ary-data)
    (FloatArrayIter. ary-data 0 (alength ^floats ary-data))
    (instance? DoubleArrayIter-ary-type ary-data)
    (DoubleArrayIter. ary-data 0 (alength ^doubles ary-data))
    :else
    (ObjectArrayIter. ary-data 0 (alength ^objects ary-data))))


(defn- map-iter
  ^Iterator [map-fn obj]
  (let [iter (coerce/->iterator obj)]
    (reify Iterator
      (hasNext [this] (.hasNext iter))
      (next [this]
        (map-fn (.next iter))))))



(def ^{:tag java.util.function.BiConsumer} default-obj-fn
  (reify BiConsumer
    (accept [this w value]
      (let [^JSONWriter w w]
        (cond
          (instance? List value)
          (.writeArray w (coerce/->iterator value))
          (instance? Map value)
          (.writeMap w (map-iter (fn [^Map$Entry e]
                                   (MapEntry. (->json-data (.getKey e))
                                              (.getValue e)))
                                 (.entrySet ^Map value)))
          (.isArray (.getClass ^Object value))
          (.writeArray w (ary-iter value))
          :else
          (.writeObject w (->json-data value)))))))


(defn json-writer-fn
  "Return a function that when called efficiently constructs a JSONWriter from the given
  options.  Same arguments as [[write-json]]."
  [options]
  (let [esc-js? (boolean (get options :escape-js-separators true))
        esc-uni? (boolean (get options :escape-unicode true))
        esc-slash? (boolean (get options :escape-slash true))
        ^String indent-str (if-let [opt  (get options :indent-str "  ")]
                             (str opt)
                             nil)
        obj-fn (coerce/->bi-consumer (get options :obj-fn default-obj-fn))]
    #(JSONWriter. (io/writer %) esc-js? esc-slash? esc-uni? indent-str obj-fn)))


(defn write-json
  "Write json to output.

   Options:

  * `:escape-unicode` - If true (default) non-ASCII characters are escaped as \\uXXXX
  * `:escape-js-separators` If true (default) the Unicode characters U+2028 and U+2029 will
       be escaped as \\u2028 and \\u2029 even if :escape-unicode is
       false. (These two characters are valid in pure JSON but are not
       valid in JavaScript strings.
  * `:escape-slash` If true (default) the slash / is escaped as \\/
  * `:indent-str` Defaults to \"  \".  When nil json is printed raw with no indent or
     whitespace.
  * `:obj-fn` - Function called on each non-primitive object - it is passed the JSONWriter and
     the object.  The default iterates maps, lists, and arrays converting anything that is
     not a json primitive or a map, list or array to a json primitive via str.  java.sql.Date
     classes get special treatment and are converted to instants which then converted to
     json primitive objects via the PToJSon protocol fn ->json which probaby defaults to
     `toString`."
  [output data & args]
  (let [argmap (apply hash-map args)
        writer-fn (json-writer-fn argmap)]
    (with-open [^JSONWriter writer (writer-fn output)]
      (.writeObject writer data))))


(defn write-json-str
  "Write json to a string.  See options for [[write-json]]."
  [data & args]
  (let [w (StringWriter.)]
    (apply write-json w data args)
    (.toString w)))


(defn write-json-fn
  "Return a function of two arguments,  (output,data), that efficiently constructs
  a json writer and writes the data. This is the most efficient pathway when writing
  a bunch of small json objects as it avoids the cost associated with unpacking the
  argument map.  Same arguments as [[write-json]]."
  [argmap]
  (let [writer-fn (json-writer-fn argmap)]
    (fn [output data]
      (with-open [^JSONWriter writer (writer-fn output)]
        (.writeObject writer data)))))
