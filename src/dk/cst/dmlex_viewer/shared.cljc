(ns dk.cst.dmlex-viewer.shared
  "Pure helpers shared between the frontend and the JVM exports.

  Coupling zone: an edit here changes the content of both surfaces,
  and no automated check renders the Apple bundle, so assume it also
  changed Dictionary.app. A surface that must diverge stops calling
  the helper; a helper never gets per-surface options."
  (:require [clojure.string :as str]))

(defn tr
  "The translation of the UI string `s` in the `ui` table, or `s`
  itself; the count `n` fills the {n} placeholder of a template.

  The English string is its own key, so the table maps English chrome
  to the dataset's language, e.g. {\"all forms\" \"alle former\"}."
  ([ui s]
   (get ui s s))
  ([ui s n]
   (str/replace (tr ui s) "{n}" (str n))))

(defn en
  "The lang attribute of the UI string `s`: \"en\" while the `ui` table
  leaves it untranslated, nil once the dataset supplies its own."
  [ui s]
  (when-not (contains? ui s) "en"))

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

(defn paradigm-slot
  "The paradigm slot of the inflected `form`: its description or tag."
  [{:keys [description tag]}]
  (or description tag))

(defn inflection-line
  "The inflected `forms` of `headword` that the run-in inflection line
  shows: one representative per paradigm slot, or nil when none remain.

  The representative is the form with a reduced short when its slot
  has one; variant spellings and forms spelled like the headword stay
  in the paradigm table and the search index."
  [headword forms]
  (->> (partition-by #(or (paradigm-slot %) (:text %)) forms)
       (map (fn [slot]
              (or (first (filter :short slot))
                  (first slot))))
       (remove #(= headword (:text %)))
       (distinct-by #(or (:short %) (:text %)))
       (not-empty)))

(defn sense-label
  "The index label of `sense`: its indicator, or the text of its first
  definition."
  [{:keys [indicator definitions]}]
  (or indicator (:text (first definitions))))

(defn label-title
  "The tooltip of a `label` shown away from the labels block: the
  display name of its type, then its own description.

  The display name comes from the config's rename, so it is already in
  the language of the export."
  [{:keys [type display description]}]
  (not-empty (str/join ": " (remove nil? [(or display type) description]))))

(defn elaboration-url
  "The source `elaboration` of an example when it is a URL, else nil.

  A URL-shaped elaboration is the deep link of the citation — e.g. the
  DDO definition behind a DanNet sense — so it becomes the link target
  and `source-title` omits it from the tooltip."
  [elaboration]
  (when (and elaboration (str/starts-with? elaboration "http"))
    elaboration))

(defn source-title
  "The tooltip of an example's cited source: the `description` of the
  source and its `elaboration`, joined. A URL-shaped elaboration is a
  link target, not tooltip text, and stays out."
  [description elaboration]
  (not-empty (str/join " " (remove nil? [description
                                         (when-not (elaboration-url elaboration)
                                           elaboration)]))))

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

(defn alphabetical-order
  "A comparator for the members of one relation row: strictly the
  `:headword` under the `compare-headwords` collation, with any member
  `:order` of the dataset ignored."
  [compare-headwords]
  (fn [a b]
    (compare-headwords (:headword a) (:headword b))))
