(ns dk.cst.dmlex-viewer.build
  "Shard a DMLex 1.0 JSON file into the static data files of the viewer.

  Reads the single-file JSON serialization of a lexicographic resource,
  either the file itself or a zip export containing it, and writes three
  kinds of file into the output directory: manifest.json with the resource
  metadata, index.json with one row per entry for the search field, and
  one file per entry under entries/ with every tag and relation resolved
  for display, so that the frontend needs no other lookup. A Dublin Core
  metadata.json next to the DMLex file merges into manifest.json for the
  front page of the viewer. The Apple Dictionary export
  (dk.cst.dmlex-viewer.appledict) renders the same resolved entries.

  Usage: clojure -J-Xmx8g -M:build <dmlex.json|zip> [<out-dir>]"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dmlex-viewer.shared :as shared]
            [pottery.core :as pottery])
  (:import [java.text Collator]
           [java.util Locale]
           [java.util.zip ZipFile]))

(defn ->file
  "The file basename of the DMLex object `id`.

  An id that is unsafe as a filename keeps its safe characters and gains
  a hash of the original."
  [id]
  (let [safe (str/replace id #"[^A-Za-z0-9._-]" "_")]
    (if (= safe id)
      id
      (str safe "-" (Integer/toHexString (hash id))))))

(defn compact
  "Remove the nil values and empty collections of the map `m`."
  [m]
  (into {} (remove (fn [[_ v]]
                     (or (nil? v) (and (coll? v) (empty? v)))))
        m))

(defn index-by
  "Index the maps `ms` by the value of `k`."
  [k ms]
  (into {} (map (juxt k identity)) ms))

(defn ->label
  "Resolve the `tag` of a label against the `label-of` and `label-type-of`
  inventories into the display map of the viewer."
  [label-of label-type-of tag]
  (let [{:keys [description typeTag sameAs]} (label-of tag)
        type-tag (label-type-of typeTag)]
    (compact {:tag             tag
              :type            typeTag
              :description     description
              :typeDescription (:description type-tag)
              :typeUri         (first (:sameAs type-tag))
              :uri             (first sameAs)})))

(defn affix
  "The short display form of the inflected `form` of `headword`,
  e.g. -t for mennesket.

  The suffix after their longest common prefix, or the prefix notation
  when the form instead shares its ending with the headword. Nil when
  the reduction would mislead: a stem change, a remainder with a space
  or without letters (a form identical to the headword), or a remainder
  ending in a hyphen (a compound stem). A multiword expression that
  inflects internally fails those checks by itself, so one that merely
  extends its last word still reduces."
  [headword form]
  (let [lcp    (count (take-while identity (map = headword form)))
        lcs    (count (take-while identity (map = (reverse headword)
                                                 (reverse form))))
        tail   (subs form lcp)
        head   (subs form 0 (- (count form) lcs))
        ok?    (fn [remainder]
                 (and (not (str/includes? remainder " "))
                      (not (str/ends-with? remainder "-"))
                      (re-find #"\p{L}" remainder)))
        suffix (when (and (>= lcp (max 2 (quot (inc (count headword)) 2)))
                          (ok? tail))
                 (str "-" tail))
        prefix (when (and (> lcs lcp)
                          (>= lcs (max 3 (quot (* 2 (count headword)) 3)))
                          (ok? head))
                 (str head "-"))]
    (or suffix prefix)))

(defn ->inflected-form
  "Resolve one inflected `form` of `headword` against the `form-tag-of`
  inventory and the label inventories in `->label*`."
  [form-tag-of ->label* headword {:keys [tag text labels]}]
  (compact {:tag         tag
            :description (:description (form-tag-of tag))
            :text        text
            :short       (affix headword text)
            :labels      (mapv ->label* labels)}))

(defn text-runs
  "The display runs of `text` under its stand-off `headword-markers` and
  `collocate-markers`, or nil when it has no markers.

  A run is {:text ...}, marked runs also carry :marker (\"headword\" or
  \"collocate\") and the collocate's :lemma. A marker that overlaps an
  earlier one or falls outside the text is ignored."
  [text headword-markers collocate-markers]
  (when-let [markers (->> (concat (map #(assoc % :marker "headword")
                                       headword-markers)
                                  (map #(assoc % :marker "collocate")
                                       collocate-markers))
                          (sort-by (juxt :startIndex :endIndex))
                          (seq))]
    (loop [pos 0, markers markers, runs []]
      (if-let [{:keys [startIndex endIndex marker lemma]} (first markers)]
        (if (and (<= pos startIndex)
                 (< startIndex endIndex)
                 (<= endIndex (count text)))
          (recur endIndex (rest markers)
                 (cond-> runs
                   (< pos startIndex) (conj {:text (subs text pos startIndex)})
                   true (conj (compact {:text   (subs text startIndex endIndex)
                                        :marker marker
                                        :lemma  lemma}))))
          (recur pos (rest markers) runs))
        (cond-> runs
          (< pos (count text)) (conj {:text (subs text pos)}))))))

(defn ->definition
  "Resolve one `definition` against the `deftype-of` inventory, with its
  marker runs."
  [deftype-of {:keys [text definitionType headwordMarkers collocateMarkers]}]
  (compact {:text            text
            :type            definitionType
            :typeDescription (:description (deftype-of definitionType))
            :runs            (text-runs text headwordMarkers collocateMarkers)}))

;; TODO: no known dataset exercises example labels yet; only unit-tested.
(defn ->example
  "Resolve one example against the `source-of` inventory and the label
  resolver `->label*`."
  [source-of ->label* {:keys [text sourceIdentity sourceElaboration labels
                              headwordMarkers collocateMarkers]}]
  (let [{:keys [description sameAs]} (source-of sourceIdentity)]
    (compact {:text              text
              :runs              (text-runs text headwordMarkers collocateMarkers)
              :labels            (mapv ->label* labels)
              :source            sourceIdentity
              :sourceDescription description
              :sourceUri         (first sameAs)
              :sourceElaboration sourceElaboration})))

(defn ->collator
  "The collator of `lang-code`, which orders headwords the way the language
  does rather than the way their code points fall."
  [lang-code]
  (Collator/getInstance (Locale/forLanguageTag (or lang-code ""))))

(defn member-refs
  "All member refs of the `relations`, as a map of ref -> relation indices."
  [relations]
  (reduce (fn [m [i {:keys [members]}]]
            (reduce #(update %1 (:ref %2) (fnil conj []) i) m members))
          {}
          (map-indexed vector relations)))

;; TODO: no known dataset exercises the relation :note, the role description
;; or the "none" hint yet; only unit-tested.
(defn relation-rows
  "The display rows for the object `ref` under the lookups of `env`.

  Each row holds the members of one other role, merged across the
  relations that share the relation type, the roles of `ref` inside it,
  and the member role, in the listing order of the dataset and with the
  `obverseListingOrder` of each member as its `:order`, so the displays
  can collate. The description of a relation instance becomes the row's
  `:note` and the description of the role's memberType its
  `:roleDescription`. In a relation with more than one role, the members
  that share the role of `ref` are its co-members rather than its
  relata, so they are left out — as is any member whose memberType hints
  \"none\"."
  [{:keys [relations reltype-of resolve-ref ref->idxs]} ref]
  (let [rows (for [i (ref->idxs ref)
                   :let [{:keys [type members description]} (nth relations i)
                         mt-of  (index-by :role (:memberTypes (reltype-of type)))
                         own    (into #{} (comp (filter #(= ref (:ref %)))
                                                (map :role))
                                      members)
                         multi? (> (count (distinct (map :role members))) 1)
                         others (cond->> (remove #(= ref (:ref %)) members)
                                  multi?  (remove (comp own :role))
                                  :always (remove #(= "none" (:hint (mt-of (:role %))))))]
                   {:keys [role] :as m} others
                   :let [target (resolve-ref (:ref m))]
                   :when target]
               {:key    [type own role]
                :type   type
                :role   role
                :note   description
                :order  (:obverseListingOrder m)
                :target target})]
    (for [[[type _ role] ms] (group-by :key rows)
          :let [{:keys [description sameAs memberTypes]} (reltype-of type)]]
      (compact {:type            type
                :role            role
                :description     description
                :roleDescription (:description ((index-by :role memberTypes) role))
                :note            (some :note ms)
                :uri             (first sameAs)
                :members         (->> ms
                                      (map (fn [{:keys [order target]}]
                                             (compact (assoc target :order order))))
                                      (shared/distinct-by #(dissoc % :order))
                                      (vec))}))))

(defn ->entry-file
  "The fully resolved display map of `entry` under the lookups of `env`.

  Every tag is expanded through the inventory indices, and every
  relation the entry or one of its senses is a member of appears as
  pre-resolved rows. An entry that shares its headword and part of
  speech with others carries the ordered files of the whole group as
  :homographs, which the web viewer merges into one page."
  [{:keys [label-of label-type-of deftype-of form-tag-of pos-of source-of
           homographs-of]
    :as   env}
   {:keys [id headword homographNumber partsOfSpeech labels inflectedForms
           senses]}]
  (let [->label*   (partial ->label label-of label-type-of)
        rows-of    (fn [ref]
                     (when ref
                       (->> (relation-rows env ref)
                            (sort-by (juxt :type (comp str :role)))
                            (vec))))
        ->sense    (fn [{:keys [id indicator labels definitions examples
                                headwordTranslations]}]
                     (compact {:id           id
                               :indicator    indicator
                               :labels       (mapv ->label* labels)
                               :definitions  (mapv (partial ->definition deftype-of)
                                                   definitions)
                               :examples     (mapv (partial ->example source-of
                                                            ->label*)
                                                   examples)
                               :translations (mapv (fn [{:keys [text langCode]}]
                                                     (compact {:text text
                                                               :lang langCode}))
                                                   headwordTranslations)
                               :relations    (rows-of id)}))]
    (compact {:id              id
              :file            (->file id)
              :headword        headword
              :homographNumber homographNumber
              :homographs      (let [group (homographs-of
                                             [headword partsOfSpeech])]
                                 (when (next group) group))
              :partsOfSpeech   (mapv (fn [tag]
                                       (let [{:keys [description sameAs]} (pos-of tag)]
                                         (compact {:tag         tag
                                                   :description description
                                                   :uri         (first sameAs)})))
                                     partsOfSpeech)
              :labels          (mapv ->label* labels)
              :inflectedForms  (mapv (partial ->inflected-form form-tag-of ->label* headword)
                                     inflectedForms)
              :senses          (mapv ->sense senses)
              :relations       (rows-of id)})))

(defn ->env
  "The lookup environment of `resource`: the inventory indices, the relation
  attachment map and the member ref resolver."
  [{:keys [entries labelTags labelTypeTags definitionTypeTags partOfSpeechTags
           inflectedFormTags sourceIdentityTags relations relationTypes]}]
  (let [sense-home (into {} (for [{:keys [id headword senses]} entries
                                  {sense-id :id :keys [indicator]} senses
                                  :when sense-id]
                              [sense-id (compact {:headword  headword
                                                  :file      (->file id)
                                                  :sense     sense-id
                                                  :indicator indicator})]))
        entry-home (into {} (for [{:keys [id headword]} entries]
                              [id {:headword headword
                                   :file     (->file id)}]))]
    {:homographs-of (reduce (fn [m {:keys [id headword partsOfSpeech]}]
                              (update m [headword partsOfSpeech]
                                      (fnil conj []) (->file id)))
                            {} entries)
     :label-of      (index-by :tag labelTags)
     :label-type-of (index-by :tag labelTypeTags)
     :deftype-of    (index-by :tag definitionTypeTags)
     :pos-of        (index-by :tag partOfSpeechTags)
     :form-tag-of   (index-by :tag inflectedFormTags)
     :source-of     (index-by :tag sourceIdentityTags)
     :reltype-of    (index-by :type relationTypes)
     :relations     (vec relations)
     :ref->idxs     (member-refs relations)
     :resolve-ref   (some-fn sense-home entry-home)}))

(defn index-rows
  "The search index of `resource`: one row per entry, sorted by headword
  with the collation of its `langCode`.

  A row is [headword file pos homographNumber]. The pos column prefers
  the description of each partOfSpeechTag over its technical tag."
  [{:keys [langCode partOfSpeechTags entries]}]
  (let [collator (->collator langCode)
        pos-of   (index-by :tag partOfSpeechTags)
        pos-name (fn [tag]
                   (or (:description (pos-of tag)) tag))]
    (->> entries
         (map (fn [{:keys [id headword homographNumber partsOfSpeech]}]
                [headword (->file id)
                 (str/join ", " (map pos-name partsOfSpeech))
                 homographNumber]))
         (sort-by first collator)
         (vec))))

(defn ->input
  "The input interface of the path `in`: either a DMLex JSON file or a
  zip export containing one.

  A map of :dmlex-file, the filename of the DMLex JSON, and
  :content-of, which returns the content of a named file sitting next
  to it (in the same directory, or in the same folder of the zip), or
  nil when the file is absent. In a zip, the DMLex file is the first
  .json entry that is neither a companion nor a hidden file. A missing
  `in` throws."
  [in]
  (when-not (.exists (io/file in))
    (throw (ex-info (str "No such input file: " in) {:in in})))
  (if (str/ends-with? in ".zip")
    (let [names  (with-open [zip (ZipFile. (io/file in))]
                   (->> (enumeration-seq (.entries zip))
                        (remove #(.isDirectory %))
                        (mapv #(.getName %))))
          dmlex  (or (first (for [name names
                                  :let [base (.getName (io/file name))]
                                  :when (and (str/ends-with? base ".json")
                                             (not (str/starts-with? base "."))
                                             (not (#{"metadata.json"
                                                     "presentation.json"} base)))]
                              name))
                     (throw (ex-info (str "No DMLex JSON file in " in)
                                     {:names names})))
          folder (.getParent (io/file dmlex))]
      {:dmlex-file (.getName (io/file dmlex))
       :content-of (fn [filename]
                     (with-open [zip (ZipFile. (io/file in))]
                       (when-let [entry (.getEntry zip (if folder
                                                         (str folder "/" filename)
                                                         filename))]
                         (slurp (.getInputStream zip entry)))))})
    (let [dir (or (.getParent (io/file in)) ".")]
      {:dmlex-file (.getName (io/file in))
       :content-of (fn [filename]
                     (let [f (io/file dir filename)]
                       (when (.exists f) (slurp f))))})))

(defn read-companion
  "The JSON companion `filename` of the input, read through its
  `content-of`, or nil when the input has none.

  The known companions are the Dublin Core metadata.json and the
  presentation.json config."
  [content-of filename]
  (some-> (content-of filename) (json/read-str)))

(defn localized
  "The string `s` itself, or the entry of `lang` (falling back to English,
  then to anything) when `s` is a language-keyed map."
  [s lang]
  (if (map? s)
    (or (get s lang) (get s "en") (first (vals s)))
    s))

(defn read-ui
  "The \"ui\" translation table of the input, read through `content-of`.

  The gettext ui.po next to the DMLex file merges over the \"ui\"
  section of the presentation `config`; the po file is what translation
  tools produce from i18n/template.pot (see dk.cst.dmlex-viewer.i18n)."
  [content-of config]
  (merge (get config "ui")
         (some-> (content-of "ui.po") (pottery/read-po-str))))

(defn read-config
  "The presentation config of the input, read through `content-of`,
  with any ui.po translations merged into its \"ui\" section."
  [content-of]
  (let [config (read-companion content-of "presentation.json")
        ui     (read-ui content-of config)]
    (cond-> config (seq ui) (assoc "ui" ui))))

(defn license-name
  "The conventional short name of the Creative Commons license `url`,
  e.g. CC BY-SA 4.0 or CC0 1.0, or nil for any other license."
  [url]
  (let [[_ path version] (re-find #"creativecommons\.org/(licenses/[a-z-]+|publicdomain/zero)/([0-9.]+)"
                                  (str url))]
    (when path
      (if (= path "publicdomain/zero")
        (str "CC0 " version)
        (str "CC " (str/upper-case (subs path (count "licenses/")))
             " " version)))))

(defn ->source
  "The display map of one Dublin Core `source` of the metadata.

  Carries its title, home URI and license, every field optional. An
  all-caps abbreviation splits from the full name it precedes in
  parentheses, e.g. DDS (Det Danske Sentimentleksikon)."
  [source]
  (let [title   (get source "dc:title")
        license (get source "dc:license")
        [_ abbr full] (re-matches #"(\p{Lu}{2,})\s*\((.+)\)" (str title))]
    (compact {:title       (or abbr title)
              :full        full
              :uri         (get source "dc:identifier")
              :license     license
              :licenseName (license-name license)})))

(defn manifest
  "The metadata and the object counts of `resource`, with the fields of
  its Dublin Core companion `metadata` merged in for the front page."
  [{:keys [title uri langCode entries relations] :as resource} metadata]
  (let [lang    (or (get metadata "dc:language") langCode)
        license (get metadata "dc:license")]
    (compact {:title       (or (get metadata "dc:title") title)
              :uri         (or (get metadata "dc:identifier") uri)
              :langCode    lang
              :description (localized (get metadata "dc:description") lang)
              :publisher   (get metadata "dc:publisher")
              :rights      (get metadata "dc:rights")
              :license     license
              :licenseName (license-name license)
              :sources     (mapv ->source (get metadata "dc:source"))
              :entries     (count entries)
              :senses      (count (mapcat :senses entries))
              :relations   (count relations)})))

(defn write-json!
  "Write `data` as JSON to the file `f`, creating its parent directories."
  [f data]
  (io/make-parents f)
  (spit f (json/write-str data :escape-slash false :escape-unicode false)))

(defn copy-companions!
  "Copy the optional presentation companions of the input into `out`,
  read through its `content-of`.

  The companions are presentation.json — with any ui.po translations
  merged into its \"ui\" section — and the stylesheet the config names.
  The config belongs to the dataset, so the build only carries it along."
  [content-of out]
  (let [config (read-config content-of)]
    (when config
      (write-json! (io/file out "presentation.json") config))
    (when-let [css (get config "css")]
      (when-let [content (content-of css)]
        (spit (io/file out css) content)))))

(defn build!
  "Read the DMLex JSON file (or zip export) `in` and write the static
  data files of the viewer into the directory `out`."
  [in out]
  (println "Reading" in)
  (let [{:keys [dmlex-file content-of]} (->input in)
        resource (json/read-str (content-of dmlex-file) :key-fn keyword)
        metadata (read-companion content-of "metadata.json")
        env      (->env resource)
        entries  (:entries resource)]
    (println "Writing" (count entries) "entries into" out)
    (doseq [entry entries
            :let [{:keys [file] :as m} (->entry-file env entry)]]
      (write-json! (io/file out "entries" (str file ".json")) m))
    (write-json! (io/file out "index.json") (index-rows resource))
    (write-json! (io/file out "manifest.json") (manifest resource metadata))
    (copy-companions! content-of out)
    (println "Done.")))

(defn -main
  "Build the data files from the command-line arguments `in` and `out`."
  [& [in out]]
  (if in
    (build! in (or out "public/data"))
    (println "Usage: clojure -J-Xmx8g -M:build <dmlex.json|zip> [<out-dir>]"))
  (shutdown-agents))

(comment
  (build! "datasets/example-dmlex.json" "public/data")
  #_.)
