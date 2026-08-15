(ns dk.cst.dmlex-viewer.shared
  "Pure helpers shared between the frontend and the JVM exports.")

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
