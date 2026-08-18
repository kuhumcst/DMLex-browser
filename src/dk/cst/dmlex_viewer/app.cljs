(ns dk.cst.dmlex-viewer.app
  "A generic DMLex viewer: one search field over the entry index and a
  typography-first rendering of single entries.

  The app is a static site. It fetches the data files that
  dk.cst.dmlex-viewer.build writes: manifest.json, index.json and one
  pre-resolved file per entry."
  (:require [clojure.string :as str]
            [dk.cst.dmlex-viewer.presentation :as presentation]
            [dk.cst.dmlex-viewer.shared :as shared]
            [replicant.dom :as r])
  (:require-macros [dk.cst.dmlex-viewer.translations :refer [inline-tables]]))

(defonce state
  (atom {:manifest     nil
         :presentation nil
         :index        nil
         :index-error  nil
         :query        ""
         :active       nil
         :entry        nil
         :error        nil
         :collate?     nil
         :lang         nil}))

(defn fetch-json!
  "Fetch the JSON file at `path` and call `callback` with its parsed
  content.

  When the fetch fails, call `on-error` with the error; by default this
  puts the error message into the app state."
  ([path callback]
   (fetch-json! path callback
                (fn [e] (swap! state assoc :error (.-message e)))))
  ([path callback on-error]
   (-> (js/fetch path)
       (.then (fn [res]
                (if (.-ok res)
                  (.json res)
                  (throw (js/Error. (str path ": " (.-status res)))))))
       (.then (fn [data] (callback (js->clj data :keywordize-keys true))))
       (.catch (fn [e]
                 (js/console.error e)
                 (on-error e))))))

(defn load-index!
  "Fetch the search index and cache a lowercase headword on every row.

  A failure lands in :index-error rather than :error, so the search view
  can surface it even while an entry is on screen."
  []
  (fetch-json! "data/index.json"
               (fn [rows]
                 (swap! state assoc :index
                        (mapv (fn [[headword file pos hom]]
                                {:headword headword
                                 :lower    (str/lower-case headword)
                                 :file     file
                                 :pos      pos
                                 :hom      hom})
                              rows)))
               (fn [e]
                 (swap! state assoc :index-error (.-message e)))))

(defn load-presentation!
  "Fetch the optional presentation config next to the data.

  Its keys are the dataset's own tags, so they stay strings rather than
  keywords. A missing file is the normal case and leaves the state
  untouched."
  []
  (-> (js/fetch "data/presentation.json")
      (.then (fn [res] (when (.-ok res) (.json res))))
      (.then (fn [data]
               (when data
                 (swap! state assoc :presentation (js->clj data)))))
      (.catch (fn [_] nil))))

(def translations
  "The bundled UI tables by language code, inlined from i18n/*.po."
  (inline-tables))

(def ui-languages
  "The offered UI languages: the bundled tables plus English."
  (sort (conj (set (keys translations)) "en")))

(def fallback-title
  "The viewer's own name, shown when the manifest supplies no title."
  "DMLex viewer")

(defn ui-table
  "The active UI table: the bundled table of the chosen language, or of
  the resource language by default.

  The dataset's own \"ui\" table merges on top, but only while the
  choice is the resource's language, since its strings are in that
  language."
  []
  (let [{:keys [manifest presentation lang]} @state]
    (merge (get translations (or lang (:langCode manifest)))
           (when (or (nil? lang) (= lang (:langCode manifest)))
             (get presentation "ui")))))

(defn tr
  "The UI string `s`, with the count `n` in its {n} placeholder,
  translated by the active UI table.

  Untranslated strings stay English."
  ([s] (shared/tr (ui-table) s))
  ([s n] (shared/tr (ui-table) s n)))

(defn en
  "The lang attribute of the UI string `s`: \"en\" while it is
  untranslated, nil once a translation supplies its own language."
  [s]
  (shared/en (ui-table) s))

(defn matches
  "The first 100 rows of `index` whose headword begins with `query`."
  [index query]
  (let [q (str/lower-case query)]
    (into [] (comp (filter #(str/starts-with? (:lower %) q))
                   (take 100))
          index)))

(defn goto-entry!
  "Clear the search and go to the entry of the file basename `file`."
  [file]
  (swap! state assoc :query "" :active nil)
  (set! (.-hash js/location) (str "/entry/" file)))

(defn next-active
  "The active result index after pressing `key` (\"ArrowDown\" or
  \"ArrowUp\") at index `active` among `n` results.

  Nil is the search field itself: Down enters the list at the top, Up
  leaves it there."
  [key active n]
  (case key
    "ArrowDown" (if active (min (inc active) (dec n)) 0)
    "ArrowUp"   (cond
                  (nil? active)  (dec n)
                  (zero? active) nil
                  :else          (dec active))))

(defn set-active!
  "Set the active result index to `i` and scroll it into view."
  [i]
  (swap! state assoc :active i)
  (when i
    (some-> (js/document.getElementById (str "result-" i))
            (.scrollIntoView #js {:block "nearest"}))))

(defn search-keydown!
  "Handle the keydown `e` in the search field over the current result
  `rows`, `query` and `active` index.

  The arrow keys move the active result, Enter follows it (or the first
  row, or goes home on a blank query), Escape clears the search."
  [rows query active e]
  (case (.-key e)
    ("ArrowDown" "ArrowUp")
    (when (seq rows)
      (.preventDefault e)
      (set-active! (next-active (.-key e) active (count rows))))
    "Enter"
    (if (str/blank? query)
      (set! (.-hash js/location) "")
      (when-let [row (nth rows (or active 0) nil)]
        (goto-entry! (:file row))))
    "Escape"
    (swap! state assoc :query "" :active nil)
    nil))

(defn update-title!
  "Set the document title from the current entry and the manifest title."
  []
  (let [{:keys [entry manifest]} @state]
    (set! (.-title js/document)
          (str/join " – " (remove nil? [(:headword entry)
                                        (or (:title manifest)
                                            fallback-title)])))))

(defn route!
  "Load the entry of the current URL fragment, or return to the front page.

  Focus follows the navigation — to the headword or the search field — so
  that keyboard and screen-reader users land on the new content instead of
  on an element the re-render removed."
  []
  (if-let [[_ file] (re-find #"^#/entry/(.+)$" (.-hash js/location))]
    (fetch-json! (str "data/entries/" file ".json")
                 (fn [entry]
                   (swap! state assoc :entry entry :error nil)
                   (update-title!)
                   (.scrollTo js/window 0 0)
                   (some-> (js/document.querySelector "h1.headword") (.focus)))
                 (fn [e]
                   (swap! state assoc :entry nil :error (.-message e))
                   (update-title!)))
    (do (swap! state assoc :entry nil :error nil)
        (update-title!)
        (some-> (js/document.getElementById "search") (.focus)))))

;; -----------------------------------------------------------------------------
;; Views

(defn tagged
  "The `tag` as an abbr when the dataset supplies a `description` for it.

  A tag from a controlled inventory is a short form with a known
  expansion."
  [tag description]
  (if description
    [:abbr {:title description} tag]
    tag))

(defn linked
  "The hiccup `x`, linked to `uri` when the dataset supplies one."
  [uri x]
  (if uri
    [:a {:href uri :target "_blank"} x]
    x))

(defn runs-view
  "The `text` of one definition or example, with its marker `runs` — the
  marked headword in bold, a collocate with its lemma as the tooltip —
  or plain when it has none."
  [text runs]
  (if runs
    (map (fn [{:keys [text marker lemma]}]
           (case marker
             "headword"  [:b text]
             "collocate" [:span.collocate {:title lemma} text]
             text))
         runs)
    text))

(defn label-dd
  "The dd of one label: its tag, linked when the label carries a URI,
  with any combined `:qualifier` values in parentheses."
  [{:keys [tag description uri qualifier]}]
  [:dd (linked uri (tagged tag description))
   (when qualifier (str " (" qualifier ")"))])

(defn labels-view
  "The `labels` as a definition list grouped by label type, with the
  extra `class` on the list.

  E.g. domain: zoo · gender: Male."
  [class labels]
  (when (seq labels)
    (into [:dl {:class ["labels" class]}]
          (map-indexed
            (fn [i group]
              (let [{:keys [type typeDescription typeUri display]} (first group)]
                (into [:div {:replicant/key i :data-type type}
                       (if type
                         [:dt (linked typeUri (tagged (or display type)
                                                      typeDescription))]
                         [:dt.visually-hidden {:lang (en "label")} (tr "label")])]
                      (map label-dd group))))
            (partition-by :type labels)))))

(defn member-link
  "The link to the home entry of one relation member."
  [{:keys [headword file indicator]}]
  [:a {:href (str "#/entry/" file) :title indicator} headword])

(defn members-dd
  "The `members` of one relation row, folded behind a details disclosure when
  the row is long."
  [members]
  (let [links (interpose ", " (map member-link members))]
    (if (> (count members) 10)
      [:dd
       [:details
        [:summary {:lang (en "{n} entries")}
         (tr "{n} entries" (count members))]
        (into [:p.member-list] links)]]
      (into [:dd] links))))

(defn relations-dl
  "The pre-resolved `relations` rows as a definition list: the role of
  the related senses against the links to their entries."
  [relations]
  (into [:dl.relations]
        (map-indexed
          (fn [i {:keys [type role description roleDescription note uri
                         display display-role members]}]
            [:div {:replicant/key i :data-type type :data-role role}
             [:dt {:title (or note roleDescription description type)}
              (linked uri (or display-role role display type))]
             (members-dd members)])
          relations)))

(defn relations-view
  "The `relations` rows — or the titled `relation-groups` of the
  presentation config — as one navigation landmark.

  Each group renders as a section under its headline, with the group's
  description as the headline's tooltip."
  [relations relation-groups]
  (cond
    (seq relation-groups)
    [:nav {:aria-label (tr "related")}
     (map-indexed
       (fn [i {:keys [title description relations]}]
         [:section {:replicant/key i :class (when title "titled")}
          (when title [:h2.relation-group {:title description} title])
          (relations-dl relations)])
       relation-groups)]

    (seq relations)
    [:nav {:aria-label (tr "related")} (relations-dl relations)]))

(defn translations-view
  "The headword `translations` of one sense as a definition list grouped
  by language: the language code against its comma-joined equivalents."
  [translations]
  (when (seq translations)
    (into [:dl.labels.translations]
          (for [lang (distinct (map :lang translations))]
            [:div {:replicant/key lang}
             [:dt lang]
             [:dd {:lang lang}
              (str/join ", " (keep #(when (= lang (:lang %)) (:text %))
                                   translations))]]))))

(defn example-view
  "One example as a quotation with its labels and its source citation."
  [{:keys [text runs labels source sourceDescription sourceUri
           sourceElaboration]}]
  [:blockquote.example
   [:p (runs-view text runs)
    (when (seq labels)
      [:span.example-labels " ("
       (interpose ", " (map (fn [{:keys [tag description uri]}]
                              (linked uri (tagged tag description)))
                            labels))
       ")"])]
   (when source
     [:footer
      [:cite (linked sourceUri
                     (tagged source
                             (not-empty
                               (str/join " " (remove nil? [sourceDescription
                                                           sourceElaboration])))))]])])

(defn sense-view
  "The sense at index `i` as a numbered list item: the indicator, the
  definitions, the examples, the labels, the translations and the
  relations."
  [i {:keys [id indicator labels definitions translations examples
             relations relation-groups]}]
  [:li.sense {:replicant/key (or id i)}
   [:p.meaning
    (when indicator (list [:i.indicator indicator] [:span.sep "|"]))
    (into [:span.definitions]
          (interpose "; " (map (fn [{:keys [text type typeDescription runs]}]
                                 [:span.definition {:data-type type
                                                    :title     typeDescription}
                                  (runs-view text runs)])
                               definitions)))]
   (map example-view examples)
   (labels-view "sense-labels" labels)
   (translations-view translations)
   (relations-view relations relation-groups)])

(defn inflections-view
  "The inflected `forms` of `headword` as one run-in definition list.

  One representative per paradigm slot — the form with a reduced short
  when the slot has one — so variant spellings stay in the paradigm, as
  does a form spelled like the headword. The paradigm slot of each form
  stays in the markup for assistive tech; sighted readers get it as a
  tooltip."
  [headword forms]
  (when-let [forms (->> (partition-by #(or (:description %) (:tag %) (:text %))
                                      forms)
                        (map (fn [group]
                               (or (first (filter :short group))
                                   (first group))))
                        (remove #(= headword (:text %)))
                        (seq))]
    (into [:dl.inflections]
          (map-indexed
            (fn [i {:keys [tag text short description labels]}]
              [:div {:replicant/key i}
               [:dt.visually-hidden (or description tag (tr "form"))]
               [:dd {:title (if short
                              (str text (when description
                                          (str " — " description)))
                              description)}
                (or short text)
                (when (seq labels)
                  [:span.form-label " (" (str/join ", " (map :tag labels)) ")"])]])
            (shared/distinct-by #(or (:short %) (:text %)) forms)))))

(defn paradigm-view
  "The full paradigm of the inflected `forms` as a table behind a details
  disclosure.

  One row per paradigm slot; forms that share the slot — variant
  spellings — join on the row."
  [forms]
  (when (some #(or (:tag %) (:description %)) forms)
    [:details.paradigm
     [:summary {:lang (en "all forms")} (tr "all forms")]
     [:table
      [:caption.visually-hidden {:lang (en "all forms")} (tr "all forms")]
      (into [:tbody]
            (map-indexed
              (fn [i group]
                (let [{:keys [tag description]} (first group)]
                  [:tr {:replicant/key i}
                   [:th {:scope "row"} (or description tag)]
                   (into [:td]
                         (interpose ", "
                                    (map (fn [{:keys [text labels]}]
                                           (list text
                                                 (when (seq labels)
                                                   [:span.form-label
                                                    " (" (str/join ", " (map :tag labels)) ")"])))
                                         group)))]))
              (partition-by #(or (:description %) (:tag %)) forms)))]]))

(defn entry-view
  "One entry as an article: the header, the senses and the entry-level
  relations."
  [{:keys [headword homographNumber partsOfSpeech labels inflectedForms
           senses relations relation-groups]}]
  [:article.entry
   [:header
    [:h1.headword {:tabindex -1} [:dfn headword]
     (when homographNumber [:sup.hom homographNumber])]
    (when (seq partsOfSpeech)
      (into [:p.pos]
            (interpose ", " (map (fn [{:keys [tag description uri]}]
                                   (linked uri (tagged (or description tag)
                                                       (when description tag))))
                                 partsOfSpeech))))
    (inflections-view headword inflectedForms)
    (paradigm-view inflectedForms)
    (labels-view "entry-labels" labels)]
   (into [:ol.senses {:class (when (= 1 (count senses)) "single")}]
         (map-indexed sense-view senses))
   (relations-view relations relation-groups)])

(defn headword-collation
  "The headword comparator of `lang-code`, using the collator of the
  browser."
  [lang-code]
  (let [collator (js/Intl.Collator. (or lang-code js/undefined))]
    (fn [a b] (.compare collator a b))))

(defn related?
  "Does the presented `entry` or one of its senses carry relation rows?"
  [{:keys [relations relation-groups senses]}]
  (boolean (or relations relation-groups
               (some #(or (:relations %) (:relation-groups %)) senses))))

(defn lang-key
  "The localStorage key of the UI language choice for the dataset of
  `manifest`."
  [manifest]
  (str "dmlex-viewer:lang:" (or (:uri manifest) (:title manifest) "default")))

(defn set-lang!
  "Set the UI language to `code` and remember it for this dataset."
  [code]
  (swap! state assoc :lang code)
  (try
    (js/localStorage.setItem (lang-key (:manifest @state)) code)
    (catch :default _ nil)))

(defn language-name
  "The name of the language `code` in that language, via the browser."
  [code]
  (try
    (.of (js/Intl.DisplayNames. #js [code] #js {:type "language"}) code)
    (catch :default _ code)))

(defn dictionary-language
  "The registered language of the dictionary content of `manifest`.

  Shown beside the UI language control, so the choice clearly affects
  only the interface. The language name is an autonym and carries its
  own lang attribute."
  [{:keys [langCode]}]
  (when langCode
    [:span.dictionary-language {:lang (en "dictionary language")}
     (tr "dictionary language") ": "
     [:strong {:lang langCode} (language-name langCode)]]))

(defn language-select
  "The dropdown switching the UI language `lang` between the bundled
  languages and English, defaulting to the resource language of the
  `manifest` when a table for it exists."
  [lang manifest]
  (let [value (or lang (:langCode manifest))
        value (if (some #{value} ui-languages) value "en")]
    [:label.ui-language {:lang (en "UI language")}
     (tr "UI language") " "
     (into [:select
            {:on {:change (fn [e]
                            (set-lang! (.. e -target -value)))}}]
           (for [code ui-languages]
             [:option {:value code :selected (= code value)}
              (language-name code)]))]))

(defn collate-toggle
  "The checkbox switching relation members between the listing order of
  the dataset and the alphabetical collation."
  [collate?]
  [:label.member-order {:lang (en "sort alphabetically")}
   [:input {:type    "checkbox"
            :checked collate?
            :on      {:change (fn [e]
                                (swap! state assoc :collate?
                                       (.. e -target -checked)))}}]
   " " (tr "sort alphabetically")])

(defn result-headword
  "The `headword` of one search result, with the matched `query` prefix
  marked."
  [headword query]
  (let [n (count query)]
    (if (and (pos? n) (<= n (count headword)))
      (list [:mark (subs headword 0 n)] (subs headword n))
      headword)))

(defn results-view
  "The search result `rows` as a listbox, with the `query` prefix marked
  and the `active` row selected for the combobox's aria-activedescendant.

  A status line announces the row count to assistive technology."
  [rows query active]
  (list
    (let [s (if (empty? rows) "No matches" "matches: {n}")]
      [:p.result-count {:role  "status"
                        :lang  (en s)
                        :class (when (seq rows) "visually-hidden")}
       (tr s (count rows))])
    (when (seq rows)
      [:ol.results {:id "search-results" :role "listbox"
                    :aria-label (tr "Search results")}
       (map-indexed
         (fn [i {:keys [headword file pos hom]}]
           [:li {:replicant/key file :role "none"}
            [:a {:id            (str "result-" i)
                 :role          "option"
                 :aria-selected (if (= i active) "true" "false")
                 :href          (str "#/entry/" file)
                 :on            {:click (fn [_] (swap! state assoc
                                                       :query "" :active nil))}}
             (result-headword headword query)
             (when hom [:sup.hom hom])
             (when (seq pos) [:i.pos pos])]])
         rows)])))

(defn search-view
  "The search result `rows` with the `active` row selected and the `query`
  prefix marked, or the `index-error` when the index failed to load.

  Nil `rows` mean the index is still loading."
  [rows index-error query active]
  (cond
    rows        (results-view rows query active)
    index-error (let [s "Search failed to load. Reload the page."]
                  [:p.error {:lang (en s)} (tr s)])))

(defn front-matter-view
  "The front matter of the `manifest` metadata on the front page.

  The description reads as a serif lead, and the publisher, the
  licence and the rights sit in the aligned key/value voice of the
  entry labels. The source datasets form a titled group in the same
  voice, each linked to its home and paired with its licence when the
  metadata carries them. The fields come from the Dublin Core
  metadata.json that the data build merges into the manifest; without
  one, nothing renders."
  [{:keys [description publisher rights license licenseName sources]}]
  (when (or description publisher rights license (seq sources))
    [:section.front-matter {:aria-labelledby "resource-title"}
     (when description [:p.description description])
     [:dl.labels
      (when publisher
        [:div [:dt {:lang (en "publisher")} (tr "publisher")] [:dd publisher]])
      (when license
        [:div [:dt {:lang (en "licence")} (tr "licence")]
         [:dd (linked license (or licenseName license))]])
      (when rights
        [:div [:dt {:lang (en "rights")} (tr "rights")] [:dd rights]])]
     (when (seq sources)
       [:section.titled {:aria-labelledby "front-matter-sources"}
        (let [s (if (some :license sources) "sources & licences" "sources")]
          [:h2.relation-group {:id "front-matter-sources" :lang (en s)}
           (tr s)])
        (into [:dl.labels]
              (map-indexed
                (fn [i {:keys [title full uri license licenseName]}]
                  [:div {:replicant/key i}
                   [:dt (linked uri (tagged title full))]
                   [:dd (linked license (or licenseName license))]]))
              sources)])]))

(defn footer-view
  "The colophon at the foot of every view: the title, the counts and the
  URI of the resource."
  [{:keys [title uri entries senses relations] :as manifest}]
  (when manifest
    [:footer.colophon
     [:p.resource (or title "DMLex resource")
      (when uri (list " · " [:a {:href uri} uri]))]
     [:dl.stats
      [:div [:dt {:lang (en "entries")} (tr "entries")] [:dd entries]]
      [:div [:dt {:lang (en "senses")} (tr "senses")] [:dd senses]]
      [:div [:dt {:lang (en "relations")} (tr "relations")] [:dd relations]]]]))

(defn app
  "The root view over one value of the app state.

  Until the manifest or its error arrives, only the empty page sheet
  renders, so the English defaults never flash before the dataset's
  own front page. The search field and the result list form an ARIA
  combobox: focus stays in the field while aria-activedescendant
  points at the active option."
  [{:keys [manifest presentation index index-error query active entry
           error collate? lang]}]
  (if-not (or manifest error)
    [:div.container]
    (let [rows      (when (and index (seq query)) (matches index query))
          collate?  (if (some? collate?)
                      collate?
                      (= "collation" (get presentation "memberOrder")))
          presented (when entry
                      (cond->> (presentation/present-entry presentation entry)
                        collate? (presentation/collate-members
                                   (headword-collation
                                     (:langCode manifest)))))
          controls  [:div.controls
                     (or (dictionary-language manifest) [:span])
                     (if (and presented (related? presented))
                       (collate-toggle collate?)
                       [:span])
                     (language-select lang manifest)]]
      [:div.container
       [:search
        [:label.visually-hidden {:for  "search"
                                 :lang (en "Search the dictionary")}
         (tr "Search the dictionary")]
        [:input {:id                    "search"
                 :type                  "search"
                 :placeholder           (tr "Type a word to look it up.")
                 :value                 query
                 :autofocus             true
                 :enterkeyhint          "go"
                 :autocapitalize        "none"
                 :role                  "combobox"
                 :aria-autocomplete     "list"
                 :aria-expanded         (str (boolean (seq rows)))
                 :aria-controls         (when (seq rows) "search-results")
                 :aria-activedescendant (when (and active (seq rows))
                                          (str "result-" active))
                 :on                    {:input   (fn [e]
                                                    (swap! state assoc
                                                           :query (.. e -target -value)
                                                           :active nil))
                                         :keydown (fn [e]
                                                    (search-keydown! rows query
                                                                     active e))}}]]
       controls
       [:main
        (when (or (seq query) (not entry))
          [:h1 {:id    "resource-title"
                :class (if (seq query) "visually-hidden" "resource-title")}
           (or (:title manifest) fallback-title)])
        (cond
          (seq query) (search-view rows index-error query active)
          entry       (entry-view presented)
          error       [:p.error {:lang (en "The page failed to load.")}
                       (tr "The page failed to load.") " "
                       [:a {:href "#/"} (tr "Go to the front page.")]]
          :else       (front-matter-view manifest))]
       (footer-view manifest)])))

(defn render!
  "Render the app into the page from the current state."
  []
  (r/render (js/document.getElementById "app") (app @state)))

(defn init
  "Start the app: load the data files, install the routing and render."
  []
  (add-watch state ::render (fn [_ _ _ _] (render!)))
  (fetch-json! "data/manifest.json"
               (fn [{:keys [langCode] :as manifest}]
                 (swap! state assoc :manifest manifest)
                 (when-let [stored (try
                                     (js/localStorage.getItem (lang-key manifest))
                                     (catch :default _ nil))]
                   (swap! state assoc :lang stored))
                 (when langCode
                   (set! (.-lang js/document.documentElement) langCode))
                 (update-title!)))
  (load-index!)
  (load-presentation!)
  (.addEventListener js/window "hashchange" route!)
  (route!)
  (render!))
