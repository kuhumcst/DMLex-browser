(ns dk.cst.dmlex-viewer.presentation
  "Apply a per-dataset presentation config to the resolved entries of
  dk.cst.dmlex-viewer.build.

  The config is a JSON file the dataset ships next to its data. Its keys
  are the dataset's own tags and stay strings — tags need not be valid
  keywords — while the entries keep the keyword keys of the build. The
  ops are generic (set membership, sort by list position, string
  substitution), so the viewer never learns what any tag means. The web
  viewer applies them at render time and the Apple Dictionary export at
  export time, which is why this namespace is cljc."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [dk.cst.dmlex-viewer.shared :as shared]))

(defn show-labels
  "Exchange the tag and description of every label whose type the `show`
  map sends to \"description\", over the `labels` of one scope.

  The label's face and its tooltip switch places: the reader sees the
  human-readable description while the technical tag moves into the
  title. A label without a description keeps its tag."
  [show labels]
  (if (empty? show)
    labels
    (mapv (fn [{:keys [type tag description] :as label}]
            (if (and (= "description" (get show type)) description)
              (assoc label :tag description :description tag)
              label))
          labels)))

(defn combine-labels
  "Merge qualifier-type labels into their hosts per the `combine` map of
  host type -> qualifier type, over the `labels` of one scope.

  A host label gains the qualifier's values under :qualifier, rendered
  by the views as \"value (qualifier)\", and the qualifier's own labels
  disappear. A qualifier without a host present stays an ordinary
  label, so nothing is ever silently lost."
  [combine labels]
  (if (empty? combine)
    labels
    (let [of-type   (group-by :type labels)
          absorbed? (fn [t]
                      (and (contains? (set (vals combine)) t)
                           (some #(= t (get combine (:type %))) labels)))]
      (->> labels
           (remove (comp absorbed? :type))
           (mapv (fn [{:keys [type] :as label}]
                   (if-let [qs (seq (of-type (get combine type)))]
                     (assoc label :qualifier
                            (str/join ", " (map :tag qs)))
                     label)))))))

(defn present
  "Apply the `ops` map of one config section to the maps `xs` keyed by
  `k`: drop the hidden, stable-sort listed keys first in order, and
  attach renames under :display.

  Ops are the string-keyed \"order\" (vector of keys), \"hide\" (vector
  of keys), \"rename\" (key -> display name) and \"unlisted\"
  (\"after\", the default, or \"hide\"). Nil `ops` only normalize `xs`
  to a vector."
  [{:strs [order hide rename unlisted]} k xs]
  (let [hidden? (set hide)
        pos     (into {} (map-indexed (fn [i t] [t i]) order))
        after   (count order)]
    (cond->> xs
      :always             (remove (comp hidden? k))
      (= unlisted "hide") (filter (comp pos k))
      :always             (sort-by #(pos (k %) after))
      rename              (map #(if-let [d (get rename (k %))]
                                  (assoc % :display d)
                                  %))
      :always             (vec))))

(defn group-relations
  "Partition the presented relation `rows` into the `groups` of the
  config, honouring the `unlisted` policy for rows no group claims.

  Groups render in listed order as {:title :description :relations};
  rows within a group sort by their position in its \"types\". A group
  without \"types\" is the fallback for every unclaimed type. Without a
  fallback, unclaimed rows form a trailing untitled group, unless
  `unlisted` is \"hide\". Empty groups disappear."
  [groups unlisted rows]
  (let [claimed   (into #{} (mapcat #(get % "types")) groups)
        fallback? (some #(nil? (get % "types")) groups)
        unclaimed (vec (remove (comp claimed :type) rows))
        section   (fn [{:strs [title description types]}]
                    (let [rs (if types
                               (let [pos (into {} (map-indexed
                                                    (fn [i t] [t i]) types))]
                                 (->> rows
                                      (filter (comp pos :type))
                                      (sort-by (comp pos :type))
                                      (vec)))
                               unclaimed)]
                      (when (seq rs)
                        (cond-> {:relations rs}
                          title       (assoc :title title)
                          description (assoc :description description)))))]
    (-> (into [] (keep section) groups)
        (cond-> (and (seq unclaimed) (not fallback?) (not= unlisted "hide"))
          (conj {:relations unclaimed}))
        (not-empty))))

(defn resolve-links
  "Rewrite every sameAs-derived URI of the presented `entry` — :uri,
  :typeUri and :sourceUri wherever they appear — to the `resolver` URL
  with the encoded URI appended, so external links land on the
  dataset's own resource browser instead of raw vocabulary files.

  A URI already on the resolver's host stays untouched, so the
  dataset's own pages link directly. This is the one config operation
  that constructs text, and deliberately the least expressive one: a
  fixed prefix plus one percent-encoded value, no templates."
  [resolver entry]
  (let [origin  (let [i (str/index-of resolver "://")
                      j (when i (str/index-of resolver "/" (+ i 3)))]
                  (if j (subs resolver 0 j) resolver))
        reroute (fn [uri]
                  (if (str/starts-with? uri origin)
                    uri
                    (str resolver (shared/encode-uri uri))))]
    (walk/postwalk (fn [x]
                     (if (map? x)
                       (reduce (fn [m k]
                                 (cond-> m
                                   (contains? m k) (update k reroute)))
                               x
                               [:uri :typeUri :sourceUri])
                       x))
                   entry)))

(defn collate-members
  "Sort the members of every relation row of the presented `entry` — on
  the entry, its senses and any relation groups — with the member order
  of the dataset first, then the headword under `compare-headwords`.

  Without this the members keep the listing order of the dataset; the
  \"memberOrder\" config value \"collation\" and the toggle of the web
  viewer apply it."
  [compare-headwords entry]
  (let [rows*   (fn [rows]
                  (mapv (fn [row]
                          (update row :members
                                  #(vec (sort (shared/member-order compare-headwords)
                                              %))))
                        rows))
        groups* (fn [groups]
                  (mapv #(update % :relations rows*) groups))
        scope*  (fn [m]
                  (cond-> m
                    (:relations m) (update :relations rows*)
                    (:relation-groups m) (update :relation-groups groups*)))]
    (cond-> (scope* entry)
      (:senses entry) (update :senses #(mapv scope* %)))))

(defn present-entry
  "Apply the presentation `config` to the resolved display `entry` of
  the build.

  Label types and relation types are ordered, hidden and renamed via
  :display; relation roles are renamed via :display-role — on the entry
  and on each of its senses. Combined label types merge first, so a
  qualifier needs no place of its own in the order. When the config
  declares relation \"groups\", :relations becomes :relation-groups.
  A \"linkResolver\" reroutes every sameAs-derived URI through the
  dataset's resource browser. An empty `config` returns `entry`
  unchanged."
  [config entry]
  (if (empty? config)
    entry
    (let [label-ops (get config "labelTypes")
          rel-ops   (get config "relationTypes")
          groups    (get rel-ops "groups")
          role-of   (get-in config ["roles" "rename"])
          labels*   (fn [labels]
                      (->> labels
                           (show-labels (get label-ops "show"))
                           (combine-labels (get label-ops "combine"))
                           (present label-ops :type)
                           (not-empty)))
          rels*     (fn [rels]
                      (some->> (not-empty (present rel-ops :type rels))
                               (mapv (fn [row]
                                       (if-let [d (get role-of (:role row))]
                                         (assoc row :display-role d)
                                         row)))))
          section*  (fn [m]
                      (if-not (and groups (:relations m))
                        m
                        (-> m
                            (dissoc :relations)
                            (assoc :relation-groups
                                   (group-relations groups
                                                    (get rel-ops "unlisted")
                                                    (:relations m))))))
          sense*    (fn [sense]
                      (-> (cond-> sense
                            (:labels sense)    (update :labels labels*)
                            (:relations sense) (update :relations rels*))
                          (section*)))]
      (cond->> (-> (cond-> entry
                     (:labels entry)    (update :labels labels*)
                     (:relations entry) (update :relations rels*)
                     (:senses entry)    (update :senses #(mapv sense* %)))
                   (section*))
        (get config "linkResolver")
        (resolve-links (get config "linkResolver"))))))
