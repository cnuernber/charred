(ns charred.json-writer-test
  (:require [clojure.test :refer :all]
            [charred.api :as api])
  (:import (java.io ByteArrayOutputStream)
           (charred JSONWriter)))

(deftest json-writer-can-flush
  (let [*did-flush (atom false)
        oos        (proxy [ByteArrayOutputStream] []

                          (flush []
                       (reset! *did-flush true)))
        f (api/json-writer-fn nil)
        ^JSONWriter json-writer (f oos)]
    (.flush json-writer)
    (= true @*did-flush)))
