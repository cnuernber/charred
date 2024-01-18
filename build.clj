(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn])
  (:refer-clojure :exclude [compile]))

(def deps-data (edn/read-string (slurp "deps.edn")))
(def codox-data (get-in deps-data [:aliases :codox :exec-args]))
(def lib (symbol (codox-data :group-id) (codox-data :artifact-id)))
(def version (codox-data :version))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s.jar" (name lib)))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile [_]
  (b/javac {:src-dirs ["java"]
            :class-dir class-dir
            :basis basis
            :javac-opts ["-source" "8" "-target" "8" "-Xlint:unchecked"]}))

(def pom-template
  [[:licenses
    [:license
     [:name "MIT License"]
     [:url "https://github.com/cnuernber/charred/blob/master/LICENSE"]]]])

(defn jar [_]
  (compile nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :pom-data pom-template})
  
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))
