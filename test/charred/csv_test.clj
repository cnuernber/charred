(ns charred.csv-test
  (:require [charred.api :refer [read-csv] :as api]
            [clojure.test :refer [deftest is testing]]))


(deftest funky-csv
  (is (= [["a,b" "c\"def\"" "one,two" "\"a,b,c\"def\"g\""]
          ["abba" "def" "1" "2"]
          ["df" "ef" "5" ""]]
         (->> (read-csv (java.io.File. "test/data/funky.csv"))
              (vec)))))


(def ^{:private true} simple
  "Year,Make,Model
1997,Ford,E350
2000,Mercury,Cougar
")

(def ^{:private true} simple-alt-sep
  "Year;Make;Model
1997;Ford;E350
2000;Mercury;Cougar
")

(def ^{:private true} complicated
  "1997,Ford,E350,\"ac, abs, moon\",3000.00
1999,Chevy,\"Venture \"\"Extended Edition\"\"\",\"\",4900.00
1999,Chevy,\"Venture \"\"Extended Edition, Very Large\"\"\",\"\",5000.00
1996,Jeep,Grand Cherokee,\"MUST SELL!
air, moon roof, loaded\",4799.00")


(deftest reading
  (let [csv (read-csv simple)]
    (is (= (count csv) 3))
    (is (= (count (first csv)) 3))
    (is (= (first csv) ["Year" "Make" "Model"]))
    (is (= (last csv) ["2000" "Mercury" "Cougar"])))
  (let [csv (read-csv simple-alt-sep :separator \;)]
    (is (= (count csv) 3))
    (is (= (count (first csv)) 3))
    (is (= (first csv) ["Year" "Make" "Model"]))
    (is (= (last csv) ["2000" "Mercury" "Cougar"])))
  (let [csv (read-csv complicated)]
    (is (= (count csv) 4))
    (is (= (count (first csv)) 5))
    (is (= (first csv)
           ["1997" "Ford" "E350" "ac, abs, moon" "3000.00"]))
    (is (= (last csv)
           ["1996" "Jeep" "Grand Cherokee", "MUST SELL!\nair, moon roof, loaded" "4799.00"]))))


(deftest throw-if-quoted-on-eof
  (let [s "ab,\"de,gh\nij,kl,mn"]
    (try
      (doall (read-csv s))
      (is false "No exception thrown")
      (catch Exception e
        (is (or (instance? java.io.EOFException e)
                (and (instance? RuntimeException e)
                     (instance? java.io.EOFException (.getCause e)))))))))


(deftest parse-line-endings
  (let [csv (read-csv "Year,Make,Model\n1997,Ford,E350")]
    (is (= 2 (count csv)))
    (is (= ["Year" "Make" "Model"] (first csv)))
    (is (= ["1997" "Ford" "E350"] (second csv))))
  (let [csv (read-csv "Year,Make,Model\r\n1997,Ford,E350")]
    (is (= 2 (count csv)))
    (is (= ["Year" "Make" "Model"] (first csv)))
    (is (= ["1997" "Ford" "E350"] (second csv))))
  (let [csv (read-csv "Year,Make,Model\r1997,Ford,E350")]
    (is (= 2 (count csv)))
    (is (= ["Year" "Make" "Model"] (first csv)))
    (is (= ["1997" "Ford" "E350"] (second csv))))
  (let [csv (read-csv "Year,Make,\"Model\"\r1997,Ford,E350")]
    (is (= 2 (count csv)))
    (is (= ["Year" "Make" "Model"] (first csv)))
    (is (= ["1997" "Ford" "E350"] (second csv)))))


(deftest trim-leading
  (let [header (first (read-csv (java.io.File. "test/data/datatype_parser.csv")
                                :comment-char \#))]
    (is (= "word" (header 2))))
  (let [header (first (read-csv (java.io.File. "test/data/datatype_parser.csv")
                                :trim-leading-whitespace? false
                                :comment-char \#))]
    (is (= "   word" (header 2)))))


(deftest empty-file-test
  (let [data (seq (read-csv (java.io.File. "test/data/emptyfile.csv")
                            :column-whitelist ["firstcol"]))]
    (is (nil? data))))


(deftest whitelist-test
  (is (= [["char" "word"]
          ["t" "true"]
          ["f" "False"]
          ["y" "YES"]
          ["n" "NO"]
          ["T" "positive"]
          ["F" "negative"]
          ["Y" "yep"]
          ["N" "not"]
          ["A" "pos"]
          ["z" "neg"]]
         (vec (read-csv (java.io.File. "test/data/datatype_parser.csv")
                        :column-whitelist ["char" "word"]
                        :comment-char \#)))))


(deftest integer-whitelist-test
  (is (= [["char" "word"]
          ["t" "true"]
          ["f" "False"]
          ["y" "YES"]
          ["n" "NO"]
          ["T" "positive"]
          ["F" "negative"]
          ["Y" "yep"]
          ["N" "not"]
          ["A" "pos"]
          ["z" "neg"]]
         (vec (read-csv (java.io.File. "test/data/datatype_parser.csv")
                        :column-whitelist [1 2]
                        :comment-char \#)))))


(deftest allowlist-empty-file-test
  (let [data (seq (read-csv (java.io.File. "test/data/emptyfile.csv")
                            :column-allowlist ["firstcol"]))]
    (is (nil? data))))


(deftest allowlist-test
  (is (= [["char" "word"]
          ["t" "true"]
          ["f" "False"]
          ["y" "YES"]
          ["n" "NO"]
          ["T" "positive"]
          ["F" "negative"]
          ["Y" "yep"]
          ["N" "not"]
          ["A" "pos"]
          ["z" "neg"]]
         (vec (read-csv (java.io.File. "test/data/datatype_parser.csv")
                        :column-allowlist ["char" "word"]
                        :comment-char \#)))))


(deftest integer-allowlist-test
  (is (= [["char" "word"]
          ["t" "true"]
          ["f" "False"]
          ["y" "YES"]
          ["n" "NO"]
          ["T" "positive"]
          ["F" "negative"]
          ["Y" "yep"]
          ["N" "not"]
          ["A" "pos"]
          ["z" "neg"]]
         (vec (read-csv (java.io.File. "test/data/datatype_parser.csv")
                        :column-allowlist [1 2]
                        :comment-char \#)))))


(deftest csv-odd-bufsize
  ;;Test to ensure that contiuning any particular parse pathway
  ;;into a new buffer works.
  (is (= [["id" "char" "word" "bool" "boolstr" "boolean"]
          ["1" "t" "true" "true" "true" "t"]
          ["2" "f" "False" "true" "true" "y"]
          ["3" "y" "YES" "false" "false" "n"]
          ["4" "n" "NO" "false" "false" "f"]
          ["5" "T" "positive" "true" "true" "true"]
          ["6" "F" "negative" "false" "false" "false"]
          ["7" "Y" "yep" "true" "true" "positive"]
          ["8" "N" "not" "false" "false" "negative"]
          ["9" "A" "pos" "false" "False" "negative"]
          ["10" "z" "neg" "false" "false" "negative"]]
         (vec (read-csv (java.io.File. "test/data/datatype_parser.csv")
                        :bufsize 7 :n-buffers -1
                        :comment-char \#)))))


(deftest carriage-return-csv
  (is (= [["header"] ["1"]]
         (mapv vec (charred.api/read-csv "header\r1\r")))))


;;(vec (read-csv (java.io.File. "test/data/csv-comment.csv")))
(deftest csv-comment-file
  (is (= [["Geneid"
           "Chr"
           "Start"
           "End"
           "Strand"
           "Length"
           "/ExpOut/220601_NS500751_0199_AHGMNMBGXL/Out/Rep/Bams/T4-1393NDC180m-a.bam"]
          ["SP_0001" "NC_003028" "197 1558" "+" "1362" "566"]
          ["SP_0002" "NC_003028" "1717" "" "2853" "+" "1137" "603"]
          ["SP_0003" "NC_003028" "2864" "" "3112" "+" "249" "67"]
          ["SP_#003" "#NC_003028" "2864" "" "3112" "+" "249" "67"]
          ["SP_#003" "#NC_003028" "2864" "" "3112" "+" "249" "67"]]
         (vec (seq (api/read-csv-supplier (java.io.File. "test/data/csv-comment.csv")))))))


(deftest csv-comment-twitterfeed
  (is (= [["id" "label" "tweet"]
          ["1"
           "0"
           "@user when a father is dysfunctional and is so selfish he drags his kids into his dysfunction.   #run"]
          ["2"
           "0"
           "@user @user thanks for #lyft credit i can't use cause they don't offer wheelchair vans in pdx.    #disapointed #getthanked"]
          ["3" "0" "bihday your majesty"]
          ["4"
           "0"
           "#model   i love u take with u all the time in urð±!!! ððððð¦ð¦ð¦"]
          ["5" "0" "factsguide: society now    #motivation"]]
         (vec (seq (api/read-csv-supplier (java.io.File. "test/data/comment2.csv")))))))


(deftest quote-not-beginning-of-line
  (is (= [["a" "3\""] ["b" "4\""] ["c" "5"]]
         (vec (api/read-csv "a,3\"\nb,4\"\nc,5")))))


(deftest quoted-row-wout-return
  (testing "pass"
    (is (= [["text"] ["Some string"]]
           (api/read-csv "text\rSome string"))))
  (testing "throws"
    (is (= [["text"] ["Some string"]]
           (api/read-csv "text\r\"Some string\"")))))


(deftest forward-slash-quoted-chars
  (is (= 3 (count (first (read-csv (java.io.File. "test/data/alb.csv") :escape \\))))))

(deftest forward-slash-quoted-chars
  (is (every? #(== 4 (count %)) (read-csv (java.io.File. "test/data/alb2.csv") :escape \\))))
