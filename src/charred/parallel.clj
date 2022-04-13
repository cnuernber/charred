(ns charred.parallel
  "Parallelism helpers"
  (:require [charred.coerce :as coerce])
  (:import [java.util.concurrent ArrayBlockingQueue Executors ExecutorService ThreadFactory
            ForkJoinPool Callable ForkJoinTask Future]
           [java.lang AutoCloseable]
           [charred CloseableSupplier]))


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
  * `:log-fn` - When set a message is logged when the iteration is finished.  If no error
     was encounted log-fn receives the message.  If an error is encountered it receives
     the exception followed by the message.  log-fn *must* be able to take either 1 or
     2 arguments.
  * `:executor-service` - Which executor service to use to run the thread.  Defaults to
     a default one created via [[default-executor-service]].
  * `:close-fn` - Function to call to close upstream iteration.  When not provided
     src-fn is checked and if it implements AutoCloseable then it's close method is
     called."
  [src-fn & [options]]
  (let [queue-depth (long (get options :queue-depth 16))
        queue (ArrayBlockingQueue. queue-depth)
        continue?* (volatile! true)
        close-fn (or (get options :close-fn)
                     (when (instance? AutoCloseable src-fn)
                       #(.close ^AutoCloseable src-fn)))
        src-fn (coerce/->supplier src-fn)
        log-fn (get options :log-fn)
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
                      (when log-fn
                        (log-fn "queue-fn thread shutdown"))
                      (catch Exception e
                        (try
                          (log-fn e "Error closing down queue-fn thread")
                          (catch Exception ee
                            (println "Error attempting to log error - log-fn *must* take 2 arguments in the event of an error.
riginal exception: " e "\nLog fn exception:" ee))))))]
    (.submit service ^Callable run-fn)
    (QueueFn. queue close-fn*)))


(defonce ^{:tag 'long
           :const true
           :private true
           :doc "Default batch size to allow reasonable safe-point access"}
  default-max-batch-size 64000)


(defn in-fork-join-task?
  []
  (ForkJoinTask/inForkJoinPool))


(defn indexed-map-reduce
  "Execute `indexed-map-fn` over `n-groups` subranges of `(range num-iters)`.
   Then call `reduce-fn` passing in entire in order result value sequence.

  * `num-iters` - Indexes are the values of `(range num-iters)`.
  * `indexed-map-fn` - Function that takes two integers, start-idx and group-len and
    returns a value.  These values are then reduced using `reduce-fn`.
  * `reduce-fn` - Function from sequence of values to result.
  * `max-batch-size` - Safepoint batch size, defaults to 64000.  For more
    information, see [java safepoint documentation](https://medium.com/software-under-the-hood/under-the-hood-java-peak-safepoints-dd45af07d766).

  Implementation:

  This function uses the `ForkJoinPool/commonPool` to parallelize iteration over
  (range num-iters) indexes via splitting the index space up into
  `(>= n-groups ForkJoinPool/getCommonPoolParallelism)` tasks.  In order to respect
  safepoints, n-groups may be only be allowed to iterate over up to `max-batch-size`
  indexes.

  If the current thread is already in the common pool, this function executes in the
  current thread."
  ([^long num-iters indexed-map-fn reduce-fn options]
   (let [max-batch-size (if (number? options)
                          options
                          (get options :max-batch-size default-max-batch-size))
         ^ForkJoinPool pool (get options :fork-join-pool (ForkJoinPool/commonPool))
         parallelism (.getParallelism pool)]
     (if (or (< num-iters (* 2 parallelism))
             (ForkJoinTask/inForkJoinPool))
       (reduce-fn [(indexed-map-fn 0 num-iters)])
       (let [num-iters (long num-iters)
             max-batch-size (long max-batch-size)
             parallelism parallelism
             group-size (quot num-iters parallelism)
             ;;max batch size is setup so that we can play nice with garbage collection
             ;;safepoint mechanisms
             group-size (min group-size max-batch-size)
             n-groups (quot (+ num-iters (dec group-size))
                            group-size)
             ;;Get pairs of (start-idx, len) to launch callables
             common-pool pool
             ;;submit index groups
             submissions (eduction
                          (map (fn [^long callable-idx]
                                 (let [group-start (* callable-idx group-size)
                                       group-end (min (+ group-start group-size) num-iters)
                                       group-len (- group-end group-start)
                                       callable #(indexed-map-fn group-start group-len)]
                                   (.submit common-pool ^Callable callable))))
                          (range n-groups))
             ;;Submit first N items
             next-submissions (drop parallelism submissions)]
         ;;make a true lazy sequence that will block on the futures and will submit
         ;;new tasks as current onces get processed.
         (->> (eduction (map (fn [future submission] (.get ^Future future)))
                        submissions (concat next-submissions (repeat parallelism nil)))
              (reduce-fn))))))
  ([num-iters indexed-map-fn reduce-fn]
   (indexed-map-reduce num-iters indexed-map-fn reduce-fn default-max-batch-size))
  ([num-iters indexed-map-fn]
   (indexed-map-reduce num-iters indexed-map-fn dorun default-max-batch-size)))
