(ns charred.api
  "Efficient pathways to read/write csv-based formats and json.  Many of these functions
  have fast pathways for constructing the parser,writer in order to help with the case where
  you want to rapidly encode/decode a stream of small objects.  For general uses, the simply
  named read-XXX, write-XXX functions are designed to be drop-in but far more efficient
  replacements of their `clojure.data.csv` and `clojure.data.json` equivalents.


  This is based on an underlying char[] based parsing system that makes it easy to build
  new parsers and allows tight loops to iterate through loaded character arrays and are thus
  easily optimized by HotSpot.

  * [CharBuffer.java](https://github.com/cnuernber/charred/blob/master/java/chardata/CharBuffer.java) - More efficient, simpler and general than StringBuilder.
  * [CharReader.java](https://github.com/cnuernber/charred/blob/master/java/chardata/CharReader.java) - PushbackReader-like abstraction only capable of pushing back
    1 character.  Allows access to the underlying buffer and relative offset.

  On top of these abstractions you have reader/writer abstractions for java and csv.

  Many of these abstractions return a [CloseableSupplier](https://github.com/cnuernber/charred/blob/master/java/charred/CloseableSupplier.java) so you
  can simply use them with `with-open` and the underlying stream/reader will be closed when the control leaves the block.  If you read all the data
  out of the supplier then the supplier itself will close the input when finished."
  (:require [charred.coerce :as coerce]
            [charred.parallel :as parallel]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:import [charred CharBuffer CharReader CSVReader CSVReader$RowReader JSONReader
            JSONReader$ObjReader CloseableSupplier CSVWriter JSONWriter
            JSONReader$ArrayReader CharredException]
           [charred.coerce SupplierIterator]
           [java.util.concurrent ArrayBlockingQueue Executors ExecutorService ThreadFactory]
           [java.util.concurrent.atomic AtomicLong]
           [java.lang AutoCloseable]
           [java.util.function Supplier LongPredicate BiConsumer]
           [java.util Arrays Iterator NoSuchElementException BitSet List Map Map$Entry Set
            ArrayList Iterator]
           [java.util.logging Logger Level]
           [java.io Reader StringReader Writer StringWriter]
           [clojure.lang MapEntry Keyword Symbol Seqable IReduce IReduceInit]
           [java.sql Date]
           [java.time Instant]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


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
      (let [^chars next-buf (aget buffers (rem cur-buf-idx (alength buffers)))
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
  "Given a reader, return a supplier that when called reads the next buffer of the reader.
  When n-buffers is >= 0, this function iterates through a fixed number of buffers under
  the covers so you need to be cognizant of the number of actual buffers that you want to
  have present in memory. This fn also implement `AutoCloseable` and closing it will close
  the underlying reader.

  Options:

  * `:n-buffers` - Number of buffers to use.  Defaults to 6 as the queue size defaults to 4 -
  if this number is positive but too small then buffers in flight will get overwritten.  If
  n-buffers is <= 0 then buffers are allocated as needed and not reused - this is the safest
  option but also can make async loading much slower than it would be otherwise.  This must
  be at least 2 larger than queue-depth.
  * `:queue-depth` - Defaults to 4.  See comments on `:n-buffers`.
  * `:bufsize` - Size of each buffer - defaults to (* 64 1024).  Small improvements are
  sometimes seen with larger or smaller buffers.
  * `:async?` - defaults to true if the number of processors is more than one..  When true
     data is read in an async thread.
  * `:close-reader?` - When true, close input reader when finished.  Defaults to true."
  ^CloseableSupplier [rdr & [options]]
  (let [rdr (->reader rdr)
        async? (and (> (.availableProcessors (Runtime/getRuntime)) 1)
                    (get options :async? true))
        options (if async?
                  ;;You need some number more buffers than queue depth else buffers will be
                  ;;overwritten during processing.  I calculate you need at least 2  - one
                  ;;in the source thread, and one that the system is parsing.
                  (let [qd (long (get options :queue-depth 4))
                        n-buffers (long (get options :n-buffers 6))
                        n-buffers (if (> n-buffers 0)
                                    (max (+ qd 2) n-buffers)
                                    n-buffers)]
                    (assoc options :queue-depth qd
                           :async? true
                           :n-buffers n-buffers
                           :bufsize (get options :bufsize (* 64 1024))))
                  (assoc options :async? false))
        n-buffers (long (get options :n-buffers -1))
        bufsize (long (get options :bufsize (* 64 1024)))
        src-fn (if (> n-buffers 0)
                 (let [buffers (object-array n-buffers)]
                   (dotimes [idx n-buffers] (aset buffers idx (char-array bufsize)))
                   (RotatingCharBufFn.  rdr 0 buffers (get options :close-reader? true)))
                 (AllocCharBufFn. rdr bufsize (get options :close-reader? true)))]
    (if async?
      (parallel/queue-supplier src-fn options)
      src-fn)))


(defonce ^:private char-ary-cls (type (char-array 0)))


(defn reader->char-reader
  "Given a reader, return a CharReader which presents some of the same interface
  as a pushbackreader but is only capable of pushing back 1 character.  It is extremely
  quick to instantiate this object from a string or character array.

  Options:

  See options for [[reader->char-buf-supplier]]."

  (^CharReader [rdr options]
   (cond
     (string? rdr)
     (CharReader. ^String rdr)
     (instance? char-ary-cls rdr)
     (CharReader. ^chars rdr)
     :else
     (CharReader. ^Supplier (reader->char-buf-supplier rdr options))))
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
    @close-fn*)
  Iterable
  (iterator [this] (SupplierIterator. this (.get this)))
  Seqable
  (seq [this] (coerce/supplier->seq this))
  IReduce
  (reduce [this rfn]
    (coerce/reduce-supplier rfn this))
  (reduce [this rfn init]
    (coerce/reduce-supplier rfn init this)))


(defn- ->character
  [v]
  (cond
    (char? v)
    v
    (string? v)
    (do
      (when-not (== 1 (.length ^String v))
        (throw (CharredException.
                (format "CSV error - Only single character separators allowed: - \"%s\""
                        v))))
      (first v))
    (number? v)
    (unchecked-char v)))


(defn read-csv-supplier
  "Read a csv into a row supplier.  Parse algorithm the same as clojure.data.csv although
  this returns a java.util.function.Supplier which also implements AutoCloseable as well as
  `clojure.lang.Seqable` and `clojure.lang.IReduce`.

  The supplier returned derives from AutoCloseable and it will terminate the reading and
  close the underlying read mechanism (and join the async thread) if (.close supp) is called.

  For a drop-in but much faster replacement to clojure.data.csv use [[read-csv]].

  Options:

  In additon to these options, see options for [[reader->char-buf-supplier]].

  * `:async?` - Defaults to true - read the file into buffers in an offline thread.  This
     speeds up reading larger files (1MB+) by about 30%.
  * `:separator` - Field separator - defaults to \\,.
  * `:quote` - Quote specifier - defaults to //\".
  * `:escape` - Escape character - defaults to disabled.
  * `:close-reader?` - Close the reader when iteration is finished - defaults to true.
  * `:column-allowlist` - Sequence of allowed column names or indexes. `:column-whitelist` still
     works but isn't preferred.
  * `:column-blocklist` - Sequence of dis-allowed column names or indexes.  When conflicts with
     `:column-allowlist` then `:column-allowlist` wins. `:column-blacklist` still works but
     isn't preferred
  * `:comment-char` - Defaults to #.  Rows beginning with character are discarded with no
    further processing.  Setting the comment-char to nil or `(char 0)` disables comment lines.
  * `:trim-leading-whitespace?` - When true, leading spaces and tabs are ignored.  Defaults
     to true.
  * `:trim-trailing-whitespace?` - When true, trailing spaces and tabs are ignored.  Defaults
     to true
  * `:nil-empty-values?` - When true, empty strings are elided entirely and returned as nil
     values. Defaults to false.
  * `:profile` - Either `:immutable` or `:mutable`.  `:immutable` returns persistent vectors
    while `:mutable` returns arraylists."
  ^CloseableSupplier [input & [options]]
  (let [rdr (reader->char-reader input options)
        nil-empty? (boolean (get options :nil-empty-values?))
        sb (CharBuffer. (boolean (get options :trim-leading-whitespace? true))
                        (boolean (get options :trim-trailing-whitespace? true))
                        nil-empty?)
        quote (->character (get options :quote \"))
        separator (->character (get options :separator \,))
        comment (->character (if-let [cchar (get options :comment-char \#)]
                               cchar
                               (char 0)))
        escape (->character (if-let [cchar (get options :escape)]
                              cchar
                              (char 0)))
        ^JSONReader$ArrayReader array-iface (case (get options :profile :immutable)
                                              :immutable JSONReader/immutableArrayReader
                                              :mutable JSONReader/mutableArrayReader)
        row-reader (CSVReader$RowReader. rdr sb true-unary-predicate quote separator comment
                                         escape array-iface)
        ;;mutably changes row in place
        next-row (.nextRow row-reader)
        ensure-long (fn [data] (if (number? data) (long data) data))
        ^BitSet column-allowlist
        (when (or (contains? options :column-allowlist)
                  (contains? options :column-blocklist)
                  (contains? options :column-whitelist)
                  (contains? options :column-blacklist))
          (let [allowlist (when-let [data (get options :column-allowlist (get options :column-whitelist))]
                            (set (map ensure-long data)))
                blocklist (when-let [data (get options :column-blocklist (get options :column-blacklist))]
                            (set/difference (set (map ensure-long data)) (or allowlist #{})))
                indexes
                (->> next-row
                     (map-indexed
                      (fn [col-idx cname]
                        (when (or (and allowlist (or (allowlist cname) (allowlist col-idx)))
                                  (and blocklist (not (or (blocklist cname) (blocklist col-idx)))))
                          col-idx)))
                     (remove nil?)
                     (seq))
                bmp (BitSet.)]
            (doseq [idx indexes]
              (.set bmp (unchecked-int idx)))
            bmp))
        ^LongPredicate col-pred (if column-allowlist
                                  (reify LongPredicate
                                    (test [this arg]
                                      (.get column-allowlist (unchecked-int arg))))
                                  true-unary-predicate)
        close-fn* (delay
                    (when (get options :close-reader? true)
                      (.close rdr)))
        next-row (if (and next-row column-allowlist)
                   (let [^List cur-row next-row
                         nr (.size cur-row)]
                     (loop [new-row (.newArray array-iface)
                            idx 0]
                       (if (< idx nr)
                         (recur (if (.get column-allowlist idx)
                                  (.onValue array-iface new-row (.get cur-row idx))
                                  new-row)
                                (unchecked-inc idx))
                         (.finalizeArray array-iface new-row))))
                   next-row)]
    (.setPredicate row-reader col-pred)
    (if next-row
      (CSVRowSupplier. row-reader next-row close-fn*)
      (do
        @close-fn*
        (reify CloseableSupplier
          (get [this] nil)
          (close [this])
          Seqable
          (seq [this] nil))))))


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
  see [[read-csv-supplier]].

  An important note is that `:comment-char` is disabled by default during read-csv
  for backward compatibility while it is not disabled by default during
  read-csv-supplier.  Also `:close-reader?` defaults to false to match the behavior
  of data.csv."
  [input & {:as args}]
  (let [args (update args :comment-char (fn [data] (if data data nil)))]
    (-> (read-csv-supplier input (merge {:profile :immutable :close-reader? false} args))
        (seq))))

(deftype ^:private WriteCSVData [^Writer w
                                 ^java.util.concurrent.atomic.AtomicLong row-count
                                 ^CharBuffer cb])


(defn write-csv-rf
  "Returns a transduce-compatible rf that will write a csv.
  See options for [[write-csv]].

  This rf must be finalized (rf last-reduced-value) and will return the number of rows
  written in that case.

  Example:

```clojure
user> (transduce (map identity) (charred/write-csv-rf \"test.csv\") [[:a :b :c][1 2 3]])
2
user> (slurp \"test.csv\")
\":a,:b,:c\n1,2,3\n\"
```"
  ([w] (write-csv-rf w nil))
  ([w options]
   (let [^String line-end (case (get options :newline :lf)
                            :lf "\n"
                            :cr "\r"
                            :cr+lf "\r\n")
         close-writer? (get options :close-writer? (string? w))
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
         sep (unchecked-int sep)]
     (fn
       ([] (WriteCSVData. (io/writer w) (AtomicLong.) (CharBuffer.)))
       ([^WriteCSVData ws]
        (when close-writer?
          (.close ^Writer (.-w ws)))
        (.get ^AtomicLong (.-row-count ws)))
       ([^WriteCSVData ws row]
        (reduce (fn [first? field]
                  (let [field (str field)
                        ^Writer w (.-w ws)
                        ^CharBuffer cb (.-cb ws)]
                    (when-not first? (.write w sep))
                    (if (.test quote-pred field)
                      (do
                        (CSVWriter/quote field quote cb)
                        (.write w (.buffer cb) 0 (.length cb)))
                      (.write w field))
                    false))
                true
                row)
        (.write ^Writer (.-w ws) line-end)
        (.incrementAndGet ^AtomicLong (.-row-count ws))
        ws)))))


(defn write-csv
  "Writes data to writer in CSV-format.  See also [[write-csv-rf]].

   Options:

     * `:separator` - Default \\,)
     * `:quote` - Default \\\")
     * `:quote?` A predicate function which determines if a string should be quoted.
        Defaults to quoting only when necessary.  May also be the the value 'true' in which
        case every field is quoted.
     * `:newline` - `:lf` (default) or `:cr+lf`)
     * `:close-writer?` - defaults to false unless `w` is a string.  When true, close writer
        when finished."
  ([w data & {:as options}]
   (let [write-rf (write-csv-rf w options)]
     (-> (reduce write-rf (write-rf) data)
         (write-rf)))))

(defrecord JSONParseRecord [array-iface obj-iface finalize-fn string-canonicalizer])


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
            (let [key-fn (if (= key-fn keyword)
                           identity
                           (or key-fn identity))
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
        parser-fn (get options :parser-fn
                       (let [obj-iface (get options :obj-iface obj-iface-default)
                             array-iface (get options :array-iface array-iface-default)]
                         (fn []
                           (JSONParseRecord. array-iface obj-iface identity nil))))
        bigdec-fn (coerce/->function (if (get options :bigdec)
                                       #(BigDecimal. ^String %)
                                       (get options :double-fn)))
        eof-fn (coerce/->supplier
                (get options :eof-fn
                     #(if eof-error?
                        (throw (java.io.EOFException. "Unexpected end of input"))
                        eof-value)))]
    (if-let [pfn (get options :parser-fn)]
      #(let [^Map parse-map (parser-fn)]
         [(JSONReader. bigdec-fn
                       (.get parse-map :array-iface)
                       (.get parse-map :obj-iface)
                       eof-fn
                       ;;You can share the string canonicalizer between parser invocations.
                       (.get parse-map :canonical-strings))
          (.get parse-map :finalize-fn)])
      (let [obj-iface (get options :obj-iface obj-iface-default)
            array-iface (get options :array-iface array-iface-default)
            sc (if (= key-fn keyword)
                 (charred.CanonicalKeywords.)
                 (charred.CanonicalStrings.))]
        #(vector (JSONReader. bigdec-fn
                              array-iface
                              obj-iface
                              eof-fn
                              sc)
                 identity)))))



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
    @close-fn*)
  IReduceInit
  (reduce [this rfn acc]
    (loop [acc acc
           v (.get this)]
      (if (and v (not (reduced? acc)))
        (recur (rfn acc v) (.get this))
        (do
          (.close this)
          (if (reduced? acc)
            (deref acc)
            acc)))))
  Seqable
  (seq [this]
    (coerce/supplier->seq this)))



(defn read-json-supplier
  "Read one or more JSON objects.
  Returns an auto-closeable supplier that when called by default throws an exception
  if the read pathway is finished.  Input may be a character array or string (most efficient)
  or something convertible to a reader.  Options for conversion to reader are described in
  [[reader->char-reader]] although for the json case we default `:async?` to false as
  most json is just too small to benefit from async reading of the input.  For input streams
  - unlike csv - `:async?` defaults to `false` as most JSON files are relatively small -
  in the 10-100K range where async loading doesn't make much of a difference.  On a larger
  file, however, setting `:async?` to true definitely can make a large difference.

  Map keys are canonicalized using an instance of charred.StringCanonicalizer.  This results
  in less memory usage and faster performance as java strings cache their hash codes.  You can
  supply the string canonicalizer potentially pre-initialized with the `parser-fn` option.
  For an example of using the `parser-fn` option see [fjson.clj](https://github.com/cnuernber/fast-json/blob/master/src/fjson.clj#L100).

  Options:

  In addition to the options below, see options for [[reader->char-reader]].

  * `:bigdec` - When true use bigdecimals for floating point numbers.  Defaults to false.
  * `:double-fn` - If :bigdec isn't provided, use this function to parse double values.
  * `:profile` - Which performance profile to use.  This simply provides defaults to
     `:array-iface` and `:obj-iface`. The default `:immutable` value produces persistent datastructures and supports value-fn and key-fn.
     `:mutable` produces an object arrays and java.util.HashMaps - this is about
     30% faster. `:raw` produces ArrayLists for arrays and a
     JSONReader$JSONObj type with a public data member that is an ArrayList for objects.
  * `:key-fn` - Function called on each string map key.
  * `:value-fn` - Function called on each map value.  Function is passed the key and val so it
     takes 2 arguments.  If this function returns `:charred.api/elided` then
     the key-val pair will be elided from the result.
  * `:array-iface` - Implementation of JSONReader$ArrayReader called on the object array of values for a javascript array.
  * `:obj-iface` - Implementation of JSONReader$ObjReader called for each javascript
    object.  Note that providing this overrides key-fn and value-fn.
  * `:eof-error?` - Defaults to true - when eof is encountered when attempting to read an
     object throw an EOF error.  Else returns a special EOF value, controlled by the `:eof-value` option.
  * `:eof-value` - EOF value.  Defaults to the keyword `:eof`
  * `:eof-fn` - Function called if readObject is going to return EOF.  Defaults to throwing an
     EOFException.
  * `:parser-fn` - Function that overrides the array-iface and obj-iface parameters - this is
    called each time the parser is created and must return a map with at least array-iface,
    obj-iface and finalize-fn keys.  It may also optionally have a `:string-canonicalizer` key
    which, if present, must be an instance of charred.StringCanonicalizer.  Thus you can
    ensure the share string tables between parser invocations or create a context-dependent
    set of array and object interface specifications."
  ^CloseableSupplier [input & [options]]
  (let [[^JSONReader json-rdr finalize-fn] ((json-reader-fn options))
        close-fn* (delay (.close json-rdr))
        ;;default async? to false as most json files are small
        options (assoc options :async? (get options :async? false))]
    (.beginParse json-rdr (reader->char-reader input options))
    (JSONSupplier. json-rdr close-fn*)))


(defn read-json
  "Drop in replacement for clojure.data.json/read and clojure.data.json/read-str.  For options
  see [[read-json-supplier]]."
  [input & {:as args}]
  (with-open [json-fn (read-json-supplier input args)]
    (.get json-fn)))


(defn parse-json-fn
  "Return a function from input->json.  Parses the options once and thus when
  parsing many small JSON inputs where you intend to get one and only one JSON
  object from them this pathway is a bit more efficient than read-json.

  Same options as [[read-json-supplier]]."
  [& [options]]
  (let [json-rdr-fn (json-reader-fn options)
        ;;default async to false
        options (assoc options :async? (get options :async? false))]
    (fn [input]
      (let [[^JSONReader json-rdr finalize-fn] (json-rdr-fn)]
        (with-open [rdr (reader->char-reader input options)]
          (.beginParse json-rdr rdr)
          (finalize-fn (.readObject json-rdr)))))))


(defprotocol PToJSON
  "Protocol to extend support for converting items to a json-supported datastructure.
  These can be a number, a string, an implementation of java.util.List or an implementation
  of java.util.Map."
  (->json-data [item]
    "Automatic conversion of some subset of types to something acceptible to json.
Defaults to toString for types that aren't representable in json."))

(defn- fullname
  ^String [item]
  (if-let [kns (namespace item)]
    (str kns "/" (name item))
    (name item)))


(extend-protocol PToJSON
  Object
  (->json-data [item]
    (cond
      (or (instance? Map item)
          (instance? List item)
          (.isArray (.getClass ^Object item)))
      item
      (instance? java.util.Date item)
      (-> (.getTime ^java.util.Date item)
          (Instant/ofEpochMilli)
          (.toString))
      (instance? Set item)
      (doto (ArrayList.)
        (.addAll ^Collection item))
      :else
      (.toString ^Object item)))
  Boolean
  (->json-data [item] item)
  Number
  (->json-data [item] item)
  String
  (->json-data [item] item)
  Keyword
  (->json-data [item] (fullname item))
  Symbol
  (->json-data [item] (fullname item)))


(def ^{:tag java.util.function.BiConsumer
       :private true} default-obj-fn
  (reify BiConsumer
    (accept [this w value]
      (let [^JSONWriter w w]
        (let [value (when-not (nil? value) (->json-data value))]
          (cond
            (or (sequential? value)
                (instance? List value)
                (.isArray (.getClass ^Object value)))
            (.writeArray w (coerce/->iterator value))
            (instance? Map value)
            (.writeMap w (coerce/map-iter (fn [^Map$Entry e]
                                            (MapEntry. (->json-data (.getKey e))
                                                       (.getValue e)))
                                          (.entrySet ^Map value)))
            :else
            (.writeObject w value)))))))


(defn json-writer-fn
  "Return a function that when called efficiently constructs a JSONWriter from the given
  options.  Same arguments as [[write-json]]."
  [options]
  (let [esc-js? (boolean (get options :escape-js-separators true))
        esc-uni? (boolean (get options :escape-unicode true))
        esc-slash? (boolean (get options :escape-slash true))
        ^String indent-str (if-let [opt (get options :indent-str nil)]
                             (str opt)
                             nil)
        obj-fn (coerce/->bi-consumer (get options :obj-fn default-obj-fn))]
    #(JSONWriter. (io/writer %) esc-js? esc-slash? esc-uni? indent-str obj-fn)))


(defn write-json
  "Write json to output.  You can extend the writer to new datatypes by implementing
  the [[->json-data]] function of the protocol `PToJSON`.  This function need only return
  json-acceptible datastructures which are numbers, booleans, nil, lists, arrays, and
  maps.  The default type coercion will in general simply call .toString on the object.

  Options:

  * `:escape-unicode` - If true (default) non-ASCII characters are escaped as \\uXXXX
  * `:escape-js-separators` If true (default) the Unicode characters U+2028 and U+2029 will
       be escaped as \\u2028 and \\u2029 even if :escape-unicode is
       false. (These two characters are valid in pure JSON but are not
       valid in JavaScript strings.
  * `:escape-slash` If true (default) the slash / is escaped as \\/
  * `:indent-str` When nil (default) json is printed raw with no indent or whitespace. For
      two spaces of indent per level of nesting, choose \"  \".
  * `:obj-fn` - Function called on each non-primitive object - it is passed the JSONWriter and
     the object.  The default iterates maps, lists, and arrays converting anything that is
     not a json primitive or a map, list or array to a json primitive via str.  java.sql.Date
     classes get special treatment and are converted to instants which then converted to
     json primitive objects via the PToJSon protocol fn [[->json-data]] which defaults to
     `toString`.  This is the most general override mechanism where you will need to manually
     call the JSONWriter's methods.  The simpler but slightly less general pathway is to
     override the protocol method [[->json-data]]."
  [output data & {:as argmap}]
  (let [writer-fn (json-writer-fn argmap)]
    (with-open [^JSONWriter writer (writer-fn output)]
      (.writeObject writer data))))


(defn write-json-str
  "Write json to a string.  See options for [[write-json]]."
  [data & {:as args}]
  (let [w (StringWriter.)]
    (apply write-json w data (apply concat (seq args)))
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


(comment
  (defn add-all-things
    [^Supplier data]
    (let [rv (java.util.ArrayList.)]
      (try
        (loop [d (.get data)]
          (when d
            (.add rv d)
            (recur (.get data))))
        (catch Exception e (println e)))
      rv))
  )
