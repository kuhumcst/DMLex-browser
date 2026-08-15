(ns dk.cst.dmlex-viewer.app
  "A generic DMLex viewer: one search field over the entry index and a
  typography-first rendering of single entries.

  The app is a static site. It fetches the data files that
  dk.cst.dmlex-viewer.build writes: manifest.json, index.json and one
  pre-resolved file per entry."
  (:require [clojure.string :as str]
            [dk.cst.dmlex-viewer.shared :as shared]
            [replicant.dom :as r]))

(defonce state
  (atom {:manifest    nil
         :index       nil
         :index-error nil
         :query       ""
         :entry       nil
         :error       nil}))

(defn fetch-json!
  "Fetch the JSON file at `path` and call `callback` with its parsed
  content. When the fetch fails, call `on-error` with the error; by default
  this puts the error message into the app state."
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

(defn matches
  "The first `n` (default 100) rows of `index` whose headword begins with
  `query`."
  ([index query]
   (matches index query 100))
  ([index query n]
   (let [q (str/lower-case query)]
     (into [] (comp (filter #(str/starts-with? (:lower %) q))
                    (take n))
           index))))

(defn goto-entry!
  "Clear the search and go to the entry of the file basename `file`."
  [file]
  (swap! state assoc :query "")
  (set! (.-hash js/location) (str "/entry/" file)))

(defn update-title!
  "Set the document title from the current entry and the manifest title."
  []
  (let [{:keys [entry manifest]} @state]
    (set! (.-title js/document)
          (str/join " – " (remove nil? [(:headword entry)
                                        (or (:title manifest)
                                            "DMLex viewer")])))))

(defn route!
  "Load the entry of the current URL fragment, or return to the front page."
  []
  (if-let [[_ file] (re-find #"^#/entry/(.+)$" (.-hash js/location))]
    (fetch-json! (str "data/entries/" file ".json")
                 (fn [entry]
                   (swap! state assoc :entry entry :error nil)
                   (update-title!)
                   (.scrollTo js/window 0 0))
                 (fn [e]
                   (swap! state assoc :entry nil :error (.-message e))
                   (update-title!)))
    (do (swap! state assoc :entry nil :error nil)
        (update-title!))))

;; -----------------------------------------------------------------------------
;; Views

(defn tagged
  "The `tag` as an abbr when the dataset supplies a `description` for it. A
  tag from a controlled inventory is a short form with a known expansion."
  [tag description]
  (if description
    [:abbr {:title description} tag]
    tag))

(defn label-dd
  "The dd of one label: its tag, linked when the label carries a URI."
  [{:keys [tag description uri]}]
  [:dd (if uri
         [:a {:href uri :target "_blank"} (tagged tag description)]
         (tagged tag description))])

(defn labels-view
  "The `labels` as a definition list grouped by label type, with the extra
  `class` on the list, e.g. domain: zoo - gender: Male."
  [class labels]
  (when (seq labels)
    (into [:dl {:class ["labels" class]}]
          (map-indexed
            (fn [i group]
              (let [{:keys [type typeDescription]} (first group)]
                (into [:div {:replicant/key i}
                       (if type
                         [:dt (tagged type typeDescription)]
                         [:dt.visually-hidden {:lang "en"} "label"])]
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
        [:summary {:lang "en"} (str (count members) " entries")]
        (into [:p.member-list] links)]]
      (into [:dd] links))))

(defn relations-view
  "The pre-resolved `relations` rows as a navigation landmark holding a
  definition list: the role of the related senses against the links to
  their entries."
  [relations]
  (when (seq relations)
    [:nav {:aria-label "related"}
     (into [:dl.relations]
           (map-indexed
             (fn [i {:keys [type role description members]}]
               [:div {:replicant/key i}
                [:dt {:title (or description type)} (or role type)]
                (members-dd members)])
             relations))]))

(defn example-view
  "One example as a quotation with its source citation."
  [{:keys [text source sourceDescription sourceElaboration]}]
  [:blockquote.example
   [:p text]
   (when source
     [:footer
      [:cite (tagged source
                     (not-empty
                       (str/join " " (remove nil? [sourceDescription
                                                   sourceElaboration]))))]])])

(defn sense-view
  "The sense at index `i` as a numbered list item: the indicator, the
  definitions, the examples, the labels and the relations."
  [i {:keys [id indicator labels definitions examples relations]}]
  [:li.sense {:replicant/key (or id i)}
   [:p.meaning
    (when indicator (list [:i.indicator indicator] [:span.sep "|"]))
    (into [:span.definitions] (interpose "; " (map :text definitions)))]
   (map example-view examples)
   (labels-view "sense-labels" labels)
   (relations-view relations)])

(defn inflections-view
  "The inflected `forms` of `headword` as one run-in definition list. A form
  spelled like the headword stays out of the line; the paradigm keeps it.
  The paradigm slot of each form stays in the markup for assistive tech;
  sighted readers get it as a tooltip."
  [headword forms]
  (when-let [forms (seq (remove #(= headword (:text %)) forms))]
    (into [:dl.inflections]
          (map-indexed
            (fn [i {:keys [tag text short description labels]}]
              [:div {:replicant/key i}
               [:dt.visually-hidden (or description tag "form")]
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
  disclosure: one row per form, the paradigm slot as the row header."
  [forms]
  (when (some #(or (:tag %) (:description %)) forms)
    [:details.paradigm
     [:summary {:lang "en"} "all forms"]
     [:table
      (into [:tbody]
            (map-indexed
              (fn [i {:keys [tag text description labels]}]
                [:tr {:replicant/key i}
                 [:th {:scope "row"} (or description tag)]
                 [:td text
                  (when (seq labels)
                    [:span.form-label " (" (str/join ", " (map :tag labels)) ")"])]])
              forms))]]))

(defn entry-view
  "One entry as an article: the header, the senses and the entry-level
  relations."
  [{:keys [headword homographNumber partsOfSpeech labels inflectedForms
           senses relations]}]
  [:article.entry
   [:header
    [:h1.headword [:dfn headword]
     (when homographNumber [:sup.hom homographNumber])]
    (when (seq partsOfSpeech)
      (into [:p.pos]
            (interpose ", " (map (fn [{:keys [tag description]}]
                                   (tagged tag description))
                                 partsOfSpeech))))
    (inflections-view headword inflectedForms)
    (paradigm-view inflectedForms)
    (labels-view "entry-labels" labels)]
   (into [:ol.senses {:class (when (= 1 (count senses)) "single")}]
         (map-indexed sense-view senses))
   (relations-view relations)])

(defn result-headword
  "The `headword` of one search result, with the matched `query` prefix
  marked."
  [headword query]
  (let [n (count query)]
    (if (and (pos? n) (<= n (count headword)))
      (list [:mark (subs headword 0 n)] (subs headword n))
      headword)))

(defn results-view
  "The search result `rows` as a list, with the `query` prefix marked. A
  status line announces the row count to assistive technology."
  [rows query]
  (list
    [:p.result-count {:role  "status"
                      :lang  "en"
                      :class (when (seq rows) "visually-hidden")}
     (if (empty? rows)
       "No matches"
       (str (count rows) " matches shown"))]
    (when (seq rows)
      [:ol.results
       (for [{:keys [headword file pos hom]} rows]
         [:li {:replicant/key file}
          [:a {:href (str "#/entry/" file)
               :on   {:click (fn [_] (swap! state assoc :query ""))}}
           (result-headword headword query)
           (when hom [:sup.hom hom])]
          (when (seq pos) [:i.pos pos])])])))

(defn search-view
  "The search results of `query` over `index`, or the `index-error` when
  the index failed to load. Nil while the index is still loading."
  [index index-error query]
  (cond
    index       (results-view (matches index query) query)
    index-error [:p.error {:lang "en"}
                 "The search index failed to load. Reload the page to try
                  again."]))

(defn footer-view
  "The colophon at the foot of every view: the title, the counts and the
  URI of the resource."
  [{:keys [title uri entries senses relations] :as manifest}]
  (when manifest
    [:footer.colophon
     [:p.resource (or title "DMLex resource")
      (when uri (list " · " [:a {:href uri} uri]))]
     [:dl.stats {:lang "en"}
      [:div [:dt "entries"] [:dd entries]]
      [:div [:dt "senses"] [:dd senses]]
      [:div [:dt "relations"] [:dd relations]]]]))

(defn app
  "The root view over one value of the app state."
  [{:keys [manifest index index-error query entry error]}]
  [:div.container
   [:search
    [:label.visually-hidden {:for "search" :lang "en"} "Search the dictionary"]
    [:input {:id             "search"
             :type           "search"
             :placeholder    "Search…"
             :value          query
             :autofocus      true
             :enterkeyhint   "go"
             :autocapitalize "none"
             :on             {:input   (fn [e]
                                         (swap! state assoc :query
                                                (.. e -target -value)))
                              :keydown (fn [e]
                                         (when (= "Enter" (.-key e))
                                           (if (str/blank? query)
                                             (set! (.-hash js/location) "")
                                             (when-let [row (first (matches index query 1))]
                                               (goto-entry! (:file row))))))}}]]
   [:main
    (when (or (seq query) (not entry))
      [:h1.visually-hidden (or (:title manifest) "DMLex viewer")])
    (cond
      (seq query) (search-view index index-error query)
      entry       (entry-view entry)
      error       [:p.error {:lang "en"}
                   "The page failed to load. "
                   [:a {:href "#/"} "Go to the front page."]]
      :else       [:p.intro {:lang "en"}
                   "Type a word in the search field to look it up. Every
                    underlined word links to another word in the dictionary."])]
   (footer-view manifest)])

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
                 (when langCode
                   (set! (.-lang js/document.documentElement) langCode))
                 (update-title!)))
  (load-index!)
  (.addEventListener js/window "hashchange" route!)
  (route!)
  (render!))
