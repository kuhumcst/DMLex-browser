(ns dk.cst.dmlex-viewer.shared
  "Pure helpers shared between the frontend and the JVM exports."
  #?(:clj (:require [clojure.string :as str])))

(defn encode-uri
  "Percent-encode `s` as one URI component, the way JavaScript's
  encodeURIComponent does, on both platforms.

  Java's URLEncoder targets form encoding, so its output is adjusted to
  match: + becomes %20 and the characters !'()~ stay bare. One config
  must render byte-identical hrefs on both surfaces."
  [s]
  #?(:cljs (js/encodeURIComponent s)
     :clj  (-> (java.net.URLEncoder/encode ^String s "UTF-8")
               (str/replace "+" "%20")
               (str/replace "%21" "!")
               (str/replace "%27" "'")
               (str/replace "%28" "(")
               (str/replace "%29" ")")
               (str/replace "%7E" "~"))))

(defn distinct-by
  "The elements of `coll`, keeping the first occurrence of each `(f x)`."
  [f coll]
  (->> coll
       (reduce (fn [[seen out] x]
                 (let [k (f x)]
                   (if (contains? seen k)
                     [seen out]
                     [(conj seen k) (conj out x)])))
               [#{} []])
       (second)))

(defn member-order
  "A comparator for the members of one relation row: the member `:order`
  of the dataset first, then the `:headword` under the `compare-headwords`
  collation.

  A member without an order sorts after every member with one, so a
  dataset that states no order lists alphabetically and one that states
  a partial order keeps its ranked members on top."
  [compare-headwords]
  (fn [a b]
    (let [c (compare (or (:order a) ##Inf) (or (:order b) ##Inf))]
      (if (zero? c)
        (compare-headwords (:headword a) (:headword b))
        c))))
