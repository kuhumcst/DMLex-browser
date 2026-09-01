(ns dk.cst.dmlex-browser.presentation
  "Apply a per-dataset presentation config to the resolved entries of
  dk.cst.dmlex-browser.build.

  The config is a JSON file the dataset ships next to its data. Its keys
  are the dataset's own tags and stay strings — tags need not be valid
  keywords — while the entries keep the keyword keys of the build. The
  ops are generic (set membership, sort by list position, string
  substitution), so the project never learns what any tag means. The web
  app applies them at render time and the Apple Dictionary export at
  export time, which is why this namespace is cljc — and why an edit
  here also changes Dictionary.app, with no automated render to show
  it."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [dk.cst.dmlex-browser.shared :as shared]))

(defn localized
  "The display string `x` in the first of `langs` that names it: `x`
  itself when the config gives one string, or the entry for a language
  when it gives one per language.

  English stands in for a language none of `langs` names, and the
  first name in the order of the language codes stands in for that, so
  a name is never lost. The last resort is sorted rather than taken as
  it comes, or the two surfaces could read one config differently: a
  map of more than eight names is a hash map, and the platforms hash
  it in their own order."
  [langs x]
  (if (map? x)
    (or (some x langs) (get x "en") (val (first (sort-by key x))))
    x))

(defn localize
  "The `config` with every display string it carries resolved to the
  first of `langs` that names it.

  The names are the only part of a config that a language changes, so
  they resolve once here and every op downstream reads plain strings.
  One config can then ship with every export of a resource, keeping a
  tag's name beside the tag rather than in a second file that has to
  be kept in step.
  Those are the renames of label types, relation types and roles, the
  title and description of a relation group, the markers of inlined
  relation types, and the fields of the Apple bundle."
  [langs config]
  (let [name*    (partial localized langs)
        group*   #(cond-> %
                    (get % "title")       (update "title" name*)
                    (get % "description") (update "description" name*))
        section* #(cond-> %
                    (get % "rename") (update "rename" update-vals name*)
                    (map? (get % "inline")) (update "inline" update-vals name*)
                    (get % "groups") (update "groups" (partial mapv group*)))]
    (cond-> config
      (get config "labelTypes")    (update "labelTypes" section*)
      (get config "relationTypes") (update "relationTypes" section*)
      (get config "roles")         (update "roles" section*)
      (get config "appledict")     (update "appledict" update-vals name*))))

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

  A host label gains the qualifier's labels under :qualifier, rendered
  by the views as \"value (qualifier)\" with each qualifier linked when
  it carries a URI, and the qualifier's own labels disappear. A
  qualifier without a host present stays an ordinary label, so nothing
  is ever silently lost."
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
                     (assoc label :qualifier (vec qs))
                     label)))))))

(defn rank-of
  "The `types` as a map of type -> list position, for rank sorting and
  membership tests."
  [types]
  (into {} (map-indexed (fn [i t] [t i]) types)))

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
        rank    (rank-of order)
        after   (count order)]
    (cond->> xs
      :always (remove (comp hidden? k))
      (= unlisted "hide") (filter (comp rank k))
      :always (sort-by #(rank (k %) after))
      rename (map #(if-let [d (get rename (k %))]
                     (assoc % :display d)
                     %))
      :always (vec))))

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
                               (let [rank (rank-of types)]
                                 (->> rows
                                      (filter (comp rank :type))
                                      (sort-by (comp rank :type))
                                      (vec)))
                               unclaimed)]
                      (when (seq rs)
                        (cond-> {:relations rs}
                          title (assoc :title title)
                          description (assoc :description description)))))]
    (-> (into [] (keep section) groups)
        (cond-> (and (seq unclaimed) (not fallback?) (not= unlisted "hide"))
                (conj {:relations unclaimed}))
        (not-empty))))

(defn move-types
  "Move the maps whose :type `types` lists from the key `from` of
  `scope` to the key `to`, in the vector's order."
  [from to types scope]
  (let [rank     (rank-of types)
        [in out] ((juxt filter remove) (comp rank :type) (get scope from))]
    (if (empty? in)
      scope
      (cond-> (-> scope
                  (dissoc from)
                  (assoc to (vec (sort-by (comp rank :type) in))))
        (seq out) (assoc from (vec out))))))

(defn inline-labels
  "Move the labels whose type the `inline` vector lists from the
  :labels of the presented `scope` — an entry or one of its senses —
  to :inline-labels, in the vector's order.

  The views render them run-in and dot-separated: on the entry's
  part-of-speech line, and on a sense's own line below its examples.
  The ordinary label ops run first, so hide beats inline and renames
  carry over."
  [inline scope]
  (move-types :labels :inline-labels inline scope))

(defn cite-labels
  "Move the labels whose type the `cite` vector lists from the :labels
  of the presented `scope` — an entry or one of its senses — to
  :cite-labels, in the vector's order.

  The views render them as the citation of the line that heads the
  scope: the part-of-speech line of an entry, the meaning line of a
  sense. The ordinary label ops run first, so hide beats cite and
  renames carry over."
  [cite scope]
  (move-types :labels :cite-labels cite scope))

(defn fold-labels
  "Move the labels whose type the `fold` vector lists from the :labels
  of the presented `scope` — an entry or one of its senses — to
  :folded-labels, in the vector's order.

  The views render them behind a closed details disclosure after the
  visible labels. The ordinary label ops run first, so hide beats
  fold and renames carry over; cite and inline claim their types
  before fold does."
  [fold scope]
  (move-types :labels :folded-labels fold scope))

(defn fold-definitions
  "Move the definitions whose definition type the `fold` vector lists
  from the :definitions of the presented `scope` to
  :folded-definitions, in the vector's order, so they render inside
  the same details disclosure as the folded labels.

  This keeps a secondary gloss off the meaning line. The neutral view
  still shows every definition there."
  [fold scope]
  (move-types :definitions :folded-definitions fold scope))

(defn fold-translations
  "Move the :translations of the presented `scope` to
  :folded-translations when `fold?` — the \"translations\" config key
  set to \"fold\" — so they render inside the same closed details as
  the folded labels."
  [fold? {:keys [translations] :as scope}]
  (if (and fold? translations)
    (-> scope
        (dissoc :translations)
        (assoc :folded-translations translations))
    scope))

(defn inline-relations
  "Move the relation rows whose type `inline` lists from the :relations
  of the presented `scope` — an entry or one of its senses — to
  :inline-relations, in the listed order.

  The views run each row into the line that heads its scope, like
  the synonym line of a dictionary. `inline` is a vector of types, or
  a map of type -> marker, where the marker (e.g. \"=\") is shown
  before the members instead of the role. The move runs before
  grouping, so a group that lists an inlined type just ends up
  empty."
  [inline scope]
  (let [types  (if (map? inline) (vec (keys inline)) inline)
        scope' (move-types :relations :inline-relations types scope)]
    (cond-> scope'
      (and (map? inline) (:inline-relations scope'))
      (update :inline-relations
              (partial mapv #(assoc % :marker (inline (:type %))))))))

(defn hide-inflections
  "Mark the inflected `forms` whose tag the `hide` set lists with
  :line-hidden, which keeps them off the run-in inflection line while
  the paradigm table and the search terms keep every form."
  [hide forms]
  (mapv #(cond-> % (hide (:tag %)) (assoc :line-hidden true)) forms))

(defn swallowed-types
  "The `types` that the `ops` of one config section drop without naming
  them: the unlisted ones, when \"unlisted\" is \"hide\".

  A type the config hides by name is a decision. A type it never
  mentions is usually one the dataset added after it wrote the config,
  which the data build reports so that it does not disappear without a
  word. A type that \"combine\" merges into a host has not vanished:
  its values show on the host label."
  [{:strs [order unlisted combine]} types]
  (when (= unlisted "hide")
    (let [listed?   (set order)
          absorbed? (set (vals combine))]
      (->> types
           (remove #(or (listed? %) (absorbed? %)))
           (sort)
           (vec)))))

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
  (let [;; scheme://host, or the whole resolver when no path follows
        origin  (or (re-find #"^.*?://[^/]*" resolver) resolver)
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
  the entry, its senses, any relation groups and any inline rows —
  with the member comparator `compare-members` (see
  dk.cst.dmlex-browser.shared for the ranked and the strictly
  alphabetical one).

  Without this the members keep the listing order of the dataset; the
  \"memberOrder\" config value \"collation\" and the checkbox of the
  web app apply it."
  [compare-members entry]
  (let [rows*   (fn [rows]
                  (mapv (fn [row]
                          (update row :members
                                  #(vec (sort compare-members %))))
                        rows))
        groups* (fn [groups]
                  (mapv #(update % :relations rows*) groups))
        scope*  (fn [m]
                  (cond-> m
                    (:relations m) (update :relations rows*)
                    (:inline-relations m) (update :inline-relations rows*)
                    (:relation-groups m) (update :relation-groups groups*)))]
    (cond-> (scope* entry)
      (:senses entry) (update :senses #(mapv scope* %)))))

(defn present-entry
  "Apply the presentation `config` to the resolved display `entry` of
  the build.

  Label types and relation types are ordered, hidden and renamed via
  :display; relation roles are renamed via :display-role — on the entry
  and on each of its senses. Combined label types merge first, so a
  qualifier needs no place of its own in the order. Label types listed
  as \"inline\" move to the :inline-labels of the entry and of each
  sense — the entry's for the part-of-speech line, a sense's for its
  own line below the examples — and those listed as \"cite\" move to
  the :cite-labels of the entry and of each sense, for the line that
  heads each of them. Label types listed as \"fold\", definition
  types listed under \"definitionTypes\", and the :translations of a
  sense when the \"translations\" key says \"fold\" move to
  :folded-labels, :folded-definitions and :folded-translations, which
  render behind one details disclosure. Relation types listed as
  \"inline\" under \"relationTypes\" move to :inline-relations,
  which render on the line that heads their scope.
  When the config declares relation \"groups\",
  :relations becomes :relation-groups.
  An \"inflectionLine\" section marks the forms its \"hide\" vector
  lists as :line-hidden, which trims the run-in inflection line.
  A \"linkResolver\" reroutes every sameAs-derived URI through the
  dataset's resource browser. An empty `config` returns `entry`
  unchanged."
  [config entry]
  (if (empty? config)
    entry
    (let [label-ops  (get config "labelTypes")
          inline     (get label-ops "inline")
          cite       (get label-ops "cite")
          fold       (get label-ops "fold")
          rel-ops    (get config "relationTypes")
          rel-inline (get rel-ops "inline")
          groups     (get rel-ops "groups")
          def-fold   (get-in config ["definitionTypes" "fold"])
          trans-fold? (= "fold" (get config "translations"))
          role-of    (get-in config ["roles" "rename"])
          resolver   (get config "linkResolver")
          line-hide  (not-empty (set (get-in config ["inflectionLine" "hide"])))
          labels*    (fn [labels]
                       (->> labels
                            (show-labels (get label-ops "show"))
                            (combine-labels (get label-ops "combine"))
                            (present label-ops :type)
                            (not-empty)))
          rels*      (fn [rels]
                       (some->> (not-empty (present rel-ops :type rels))
                                (mapv (fn [row]
                                        (if-let [d (get role-of (:role row))]
                                          (assoc row :display-role d)
                                          row)))))
          section*   (fn [m]
                       (if-not (and groups (:relations m))
                         m
                         (-> m
                             (dissoc :relations)
                             (assoc :relation-groups
                                    (group-relations groups
                                                     (get rel-ops "unlisted")
                                                     (:relations m))))))
          sense*     (fn [sense]
                       (->> (cond-> sense
                              (:labels sense) (update :labels labels*)
                              (:relations sense) (update :relations rels*))
                            (inline-relations rel-inline)
                            (section*)
                            (cite-labels cite)
                            (inline-labels inline)
                            (fold-definitions def-fold)
                            (fold-labels fold)
                            (fold-translations trans-fold?)))
          entry*     (fn [entry]
                       (->> (cond-> entry
                              (:labels entry) (update :labels labels*)
                              (:relations entry) (update :relations rels*)
                              (:senses entry) (update :senses
                                                      #(mapv sense* %))
                              (and line-hide (:inflectedForms entry))
                              (update :inflectedForms
                                      (partial hide-inflections line-hide)))
                            (inline-relations rel-inline)
                            (section*)
                            (cite-labels cite)
                            (inline-labels inline)
                            (fold-labels fold)))]
      (cond->> (entry* entry)
        resolver (resolve-links resolver)))))

(defn present-entries
  "The homograph group `entries` presented under `config`, with the
  members of every relation row collated per `order-mode` (:alpha,
  :collation or nil) in the collation of `lang-code`.

  Presenting walks and sorts a whole group, so the result belongs in
  the app state rather than in the render path."
  [config order-mode lang-code entries]
  (let [compare-headwords (when order-mode (shared/collation lang-code))
        compare-members   (case order-mode
                            :alpha     (shared/alphabetical-order
                                         compare-headwords)
                            :collation (shared/member-order compare-headwords)
                            nil)]
    (mapv #(cond->> (present-entry config %)
             compare-members (collate-members compare-members))
          entries)))

(defn present-state
  "The app state `state` with its raw entries presented under the config
  and the member order it currently asks for.

  Both surfaces decide the same way: the dataset's config
  unless the reader switched it off, and a member order of :alpha when
  the reader forced one, of :collation when the config asks for it.

  A config that carries a name per language resolves to the language
  the reader chose, then to the resource's own. The pre-rendered pages
  take the second, since no reader has chosen yet when the data build
  writes them.

  The members collate in the language of the headwords
  (:headwordLang), which differs from :langCode when a Dublin Core
  dc:language presents the resource in another language."
  [{:keys [manifest presentation presentation? alpha? raw-entries lang]
    :as   state}]
  (let [config     (when presentation?
                     (localize (remove nil? [lang (:langCode manifest)])
                               presentation))
        order-mode (cond
                     alpha? :alpha
                     (= "collation" (get config "memberOrder")) :collation)]
    (assoc state :entries (present-entries config order-mode
                                           (or (:headwordLang manifest)
                                               (:langCode manifest))
                                           raw-entries))))
