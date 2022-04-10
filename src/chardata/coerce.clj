(ns chardata.coerce
  "Coercions to specific java types."
  (:import [java.util Iterator]
           [java.util.function Supplier Function BiFunction Consumer Predicate BiConsumer]
           [java.util.stream Stream]
           [clojure.lang IFn]))

(set! *warn-on-reflection* true)


(defn- failed-coercion-message
  ^String [item target-type]
  (format "Item type %s has no coercion to %s"
          (type item) target-type))


(defn ->iterator
  "Convert a stream or an iterable into an iterator."
  ^Iterator [item]
  (cond
    (instance? Iterator item)
    item
    (instance? Iterable item)
    (.iterator ^Iterable item)
    (instance? Stream item)
    (.iterator ^Stream item)
    (instance? Supplier item)
    (let [^Supplier item item
          curobj* (volatile! (.get item))]
      (reify Iterator
        (hasNext [this] (not (nil? @curobj*)))
        (next [this]
          (let [curval @curobj*]
            (vreset! curobj* (.get item))
            curval))))
    :else
    (throw (Exception. (failed-coercion-message item "iterator")))))


(defn ->supplier
  "Coerce an item or sequence to a java supplier"
  ^Supplier [item]
  (cond
    (nil? item) nil
    (instance? Supplier item)
    item
    (instance? IFn item)
    (reify Supplier
      (get [this] (item)))
    (or (instance? Iterable item)
        (instance? Iterator item))
    (let [iter (->iterator item)
          continue?* (volatile! (.hasNext iter))]
      (reify Supplier
        (get [this]
          (when @continue?*
            (let [retval (.next iter)]
              (vreset! continue?* (.hasNext iter))
              retval)))))
    :else
    (throw (Exception. (failed-coercion-message item "supplier")))))


(defn ->function
  "Coerce an item to a java function"
  ^Function [item]
  (cond
    (nil? item) nil
    (instance? Function item)
    item
    (instance? IFn item)
    (reify Function
      (apply [this arg] (item arg)))
    :else
    (throw (Exception. (failed-coercion-message item "function")))))


(defn ->predicate
  ^Predicate [item]
  (cond
    (nil? item) nil
    (instance? Predicate item)
    item
    (instance? IFn item)
    (reify Predicate
      (test [this arg] (boolean (item arg))))
    :else
    (throw (Exception. (failed-coercion-message item "predicate")))))


(defn ->bi-function
  "Coerce an item to a java bi-function"
  ^BiFunction [item]
  (cond
    (nil? item) nil
    (instance? BiFunction item)
    item
    (instance? IFn item)
    (reify BiFunction
      (apply [this a b] (item a b)))
    :else
    (throw (Exception. (failed-coercion-message item "bi-function")))))


(defn ->consumer
  "Coerce an item to a java consumer"
  ^Consumer [item]
  (cond
    (nil? item) nil
    (instance? Consumer item)
    item
    (instance? IFn item)
    (reify Consumer (accept [this arg] (item arg)))
    :else
    (throw (Exception. (failed-coercion-message item "consumer")))))


(defn ->bi-consumer
  "Coerce an item to a java consumer"
  ^BiConsumer [item]
  (cond
    (nil? item) nil
    (instance? BiConsumer item)
    item
    (instance? IFn item)
    (reify BiConsumer (accept [this a1 a2] (item a2 a2)))
    :else
    (throw (Exception. (failed-coercion-message item "bi-consumer")))))


(defn- do-supplier-seq
  [^Supplier sup]
  (when-let [item (.get sup)]
    (cons item (lazy-seq (do-supplier-seq sup)))))


(defn supplier->seq
  "Conversion from something convertible to a java.util.function.Supplier
  to a lazy sequence.  Has effect of un-chunking lazy sequences."
  [item]
  (do-supplier-seq (->supplier item)))


(defmacro doiter
  "Execute body for every item in the iterable.  Expecting side effects, returns nil."
  [varname iterable & body]
  `(let [iter# (->iterator ~iterable)]
     (loop [continue?# (.hasNext iter#)]
       (when continue?#
         (let [~varname (.next iter#)]
           ~@body
           (recur (.hasNext iter#)))))))


(defmacro indexed-doiter
  "Execute body for every item in the iterable.  Expecting side effects, returns nil."
  [idxvarname varname iterable & body]
  `(let [iter# (->iterator ~iterable)]
     (loop [continue?# (.hasNext iter#)
            ~idxvarname 0]
       (when continue?#
         (let [~varname (.next iter#)]
           ~@body
           (recur (.hasNext iter#) (unchecked-inc ~idxvarname)))))))
