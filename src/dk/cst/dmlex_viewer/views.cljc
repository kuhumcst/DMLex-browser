(ns dk.cst.dmlex-viewer.views
  "The hiccup of the web viewer, as pure functions over one value of the
  app state.

  Nothing here touches the DOM or the state atom: the browser renders
  these views through replicant.dom and the data build pre-renders the
  same ones through replicant.string, which is why the namespace is
  cljc. Chrome text arrives through the hiccup/tr alias, so only the views
  that translate an attribute value carry the `ui` table themselves.

  Mirrored by hand in dk.cst.dmlex-viewer.appledict: carry markup edits
  over, minding the differences listed there."
  (:require [clojure.string :as str]
            [dk.cst.dmlex-viewer.shared :as shared]
            [dk.cst.dmlex-viewer.hiccup :as hiccup]))

(def fallback-title
  "The viewer's own name, shown when the manifest supplies no title."
  "DMLex viewer")

(defn tagged
  "The `tag` as a span with the `description` of the dataset as its tooltip.

  Whether a tag abbreviates anything is the dataset's own business, so
  the markup stays a neutral span rather than an abbr."
  [tag description]
  (if description
    [:span {:title description} tag]
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

(defn inline-label-view
  "One of the `labels` the config moves onto the part-of-speech line:
  its tag, linked and with any combined `:qualifier` in parentheses.

  The display name of the type stays in the markup for assistive
  tech; the dot separator lives in CSS, so it is never announced."
  [{:keys [tag uri qualifier type display] :as label}]
  (let [attr (or display type)]
    [:span.inline-label
     (when attr [:span.visually-hidden (str attr ": ")])
     (linked uri (tagged tag (shared/label-title label)))
     (when qualifier (str " (" qualifier ")"))]))

(defn labels-view
  "The `labels` as a definition list grouped by label type, with the
  extra `class` on the list.

  E.g. domain: zoo · gender: Male."
  [class labels]
  (when (seq labels)
    (into [:dl {:class ["labels" class]}]
          (map (fn [group]
                 (let [{:keys [type typeDescription typeUri display]} (first group)]
                   (into [:div {:data-type type}
                          (if type
                            [:dt (linked typeUri (tagged (or display type)
                                                         typeDescription))]
                            [hiccup/tr {:hiccup/tag :dt.visually-hidden} "label"])]
                         (map label-dd group))))
               (partition-by :type labels)))))

(defn member-link
  "The link to the home entry of one relation member, targeting its home
  sense when the member is one."
  [{:keys [headword file sense indicator]}]
  [hiccup/a {:hiccup/entry file :hiccup/sense sense :title indicator} headword])

(defn members-dd
  "The `members` of one relation row, folded behind a details disclosure when
  the row is long."
  [members]
  (let [links (interpose ", " (map member-link members))]
    (if (> (count members) 10)
      [:dd
       [:details
        [hiccup/tr {:hiccup/tag :summary :hiccup/n (count members)} "{n} entries"]
        (into [:p.member-list] links)]]
      (into [:dd] links))))

(defn relations-dl
  "The pre-resolved `relations` rows as a definition list: the role of
  the related senses against the links to their entries."
  [relations]
  (into [:dl.relations]
        (map (fn [{:keys [type role description roleDescription note uri
                          display display-role members]}]
               [:div {:data-type type :data-role role}
                [:dt {:title (or note roleDescription description type)}
                 (linked uri (or display-role role display type))]
                (members-dd members)])
             relations)))

(defn relations-view
  "The `relations` rows — or the titled `relation-groups` of the
  presentation config — as the children of the hiccup `wrapper`.

  The entry passes a nav landmark and each sense a plain div, so a
  many-sensed entry does not repeat identically named landmarks. A
  titled group renders as a section under its headline, with the
  group's description as the headline's tooltip; an untitled group is
  a bare div of rows."
  [wrapper relations relation-groups]
  (cond
    (seq relation-groups)
    (into wrapper
          (map (fn [{:keys [title description relations]}]
                 (if title
                   [:section.titled
                    [:h2.relation-group {:title description} title]
                    (relations-dl relations)]
                   [:div (relations-dl relations)]))
               relation-groups))

    (seq relations)
    (conj wrapper
          [:section.titled
           [hiccup/tr {:hiccup/tag :h2.relation-group} "related"]
           (relations-dl relations)])))

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
  "One example as a paragraph, or as a cited quotation when it carries a
  source.

  The labels and the citation sit outside the quoted text, which is all
  a blockquote may contain."
  [{:keys [text runs labels source sourceDescription sourceUri
           sourceElaboration]}]
  (let [example (runs-view text runs)
        labels' (when (seq labels)
                  [:span.example-labels " ("
                   (interpose ", " (map (fn [{:keys [tag description uri]}]
                                          (linked uri (tagged tag description)))
                                        labels))
                   ")"])]
    (if source
      [:figure.example
       [:blockquote [:p example]]
       labels'
       [:figcaption
        [:cite (linked (or (shared/elaboration-url sourceElaboration)
                           sourceUri)
                       (tagged source (shared/source-title
                                        sourceDescription
                                        sourceElaboration)))]]]
      [:p.example example labels'])))

(defn cite-view
  "One label the config moved to the line that heads its scope: the
  name of its type, linked to whatever the label points at.

  The tag itself stays out of the text: a cited tag is usually an
  identifier rather than a word, and the type is what the reader can
  read."
  [{:keys [uri type display typeDescription]}]
  [:cite.cite-label (linked uri (tagged (or display type) typeDescription))])

(defn definitions-view
  "The `definitions` of a sense, run together divided by semicolons, and
  linked to `source` when the config cited one.

  The definition carries the link rather than a word beside it: beside
  a definition of any length, the word wraps onto a line of its own
  and reads as a gap. The name of the type follows the text for
  assistive tech, since the definition alone does not say where the
  link goes. The click stops at the link, or it would fold the sense
  it opens."
  [definitions {:keys [uri type display typeDescription] :as source}]
  (let [text (into [:span.definitions]
                   (interpose "; "
                              (map (fn [{:keys [text type typeDescription runs]}]
                                     [:span.definition {:data-type type
                                                        :title     typeDescription}
                                      (runs-view text runs)])
                                   definitions)))]
    (if uri
      [:a.definition-source {:href   uri
                             :target "_blank"
                             :title  typeDescription
                             :on     {:click [[:event/stop]]}}
       text
       [:span.visually-hidden (str " (" (or display type) ")")]]
      text)))

(defn meaning-view
  "The meaning line of a sense in the element `tag`: the `indicator`,
  the `definitions` and the `cites` that the config moved here.

  The first cite that points anywhere links the definitions; any other
  follows them."
  [tag indicator definitions cites]
  (let [source (first (filter :uri cites))]
    [tag
     (when indicator [:span.indicator indicator])
     (definitions-view definitions source)
     (map cite-view (remove #(= % source) cites))]))

(defn sense-view
  "The sense at index `i` as a numbered list item: the meaning line, the
  examples, the labels, the translations and the relations.

  A sense that carries more than its meaning line folds. The meaning
  becomes the summary of a details that opens by default, so a reader
  who has read a long sense can put its examples and relations away
  and keep the definitions in view. The `:folded` ids of `nav` hold
  which senses the reader put away, so a re-render of the sense
  restores the fold rather than springing it open. The sense id
  becomes the DOM id
  that sense-targeted navigation scrolls to and focuses; the sense
  such a navigation targeted carries
  aria-current, the sense on screen carries the margin mark via the
  on-screen class, and the sense a pending `:reveal` of `nav` names
  carries the hook that performs it."
  [ui
   {:keys [spy current reveal folded]}
   i
   {:keys [id indicator labels definitions translations examples
           relations relation-groups cite-labels]}]
  (let [body (seq (remove nil? [(seq (map example-view examples))
                                (labels-view "sense-labels" labels)
                                (translations-view translations)
                                (relations-view [:div.related] relations
                                                relation-groups)]))]
    [:li.sense (cond-> {:replicant/key (or id i)}
                 id                (assoc :id id :tabindex -1)
                 (= id current)    (assoc :aria-current "location")
                 (= id spy)        (assoc :class "on-screen")
                 (= id (:sense reveal))
                 (assoc :replicant/on-render [[:app/reveal reveal]]))
     (if body
       [:details.sense-body {:open (not (contains? folded id))
                             :on   {:toggle [[:app/fold id]]}}
        (conj (meaning-view :summary.meaning indicator definitions cite-labels)
              [:span.fold-mark
               {:lang        (shared/en ui "Show or hide the rest.")
                :title       (shared/tr ui "Show or hide the rest.")
                :aria-hidden "true"}])
        body]
       (meaning-view :p.meaning indicator definitions cite-labels))]))

(defn inflections-view
  "The inflected `forms` of `headword` as one run-in definition list,
  reduced to the representatives of shared/inflection-line.

  The paradigm slot of each form stays in the markup for assistive
  tech; sighted readers get it as a tooltip."
  [headword forms]
  (when-let [forms (shared/inflection-line headword forms)]
    (into [:dl.inflections]
          (map (fn [{:keys [tag text short description labels]}]
                 [:div
                  [:dt.visually-hidden (or description tag
                                           [hiccup/tr {} "form"])]
                  [:dd {:title (if short
                                 (str text (when description
                                             (str " — " description)))
                                 description)}
                   (or short text)
                   (when (seq labels)
                     [:span.form-label " (" (str/join ", " (map :tag labels)) ")"])]])
               forms))))

(defn paradigm-view
  "The full paradigm of the inflected `forms` as a table behind a details
  disclosure.

  One row per paradigm slot; forms that share the slot — variant
  spellings — join on the row."
  [forms]
  (when (some shared/paradigm-slot forms)
    [:details.paradigm
     [hiccup/tr {:hiccup/tag :summary} "all forms"]
     [:table
      [hiccup/tr {:hiccup/tag :caption.visually-hidden} "all forms"]
      (into [:tbody]
            (map (fn [group]
                   [:tr
                    [:th {:scope "row"} (shared/paradigm-slot (first group))]
                    (into [:td]
                          (interpose ", "
                                     (map (fn [{:keys [text labels]}]
                                            (list text
                                                  (when (seq labels)
                                                    [:span.form-label
                                                     " (" (str/join ", " (map :tag labels)) ")"])))
                                          group)))])
                 (partition-by shared/paradigm-slot forms)))]]))

(defn entry-view
  "One entry as an article under the navigation state `nav`: the header,
  the entry-level labels in their titled box, the senses and the
  entry-level relations, with `ui` naming the related landmark.

  The entry file becomes the DOM id that entry-targeted navigation
  scrolls to within a merged homograph group, and the headword carries
  the hook of a pending `:reveal` that names no sense."
  [ui {:keys [reveal] :as nav}
   {:keys [file headword homographNumber partsOfSpeech labels inline-labels
           inflectedForms senses relations relation-groups cite-labels]}]
  [:article.entry {:id file :replicant/key file}
   [:header
    [:h1.headword (cond-> {:tabindex -1}
                    (and (= file (:file reveal)) (nil? (:sense reveal)))
                    (assoc :replicant/on-render [[:app/reveal reveal]]))
     [:dfn headword]
     (when homographNumber [:sup.hom homographNumber])]
    (when (or (seq partsOfSpeech) (seq inline-labels) (seq cite-labels))
      [:p.pos
       (when (seq partsOfSpeech)
         (into [:span.pos-list]
               (interpose ", " (map (fn [{:keys [tag description uri]}]
                                      (linked uri (tagged (or description tag)
                                                          (when description tag))))
                                    partsOfSpeech))))
       (map inline-label-view inline-labels)
       (map cite-view cite-labels)])
    (inflections-view headword inflectedForms)
    (paradigm-view inflectedForms)]
   (when (seq labels)
     [:section.titled
      [hiccup/tr {:hiccup/tag :h2.relation-group} "about the word"]
      (labels-view "entry-labels" labels)])
   (into [:ol.senses {:class (when (= 1 (count senses)) "single")}]
         (map-indexed (partial sense-view ui nav) senses))
   (relations-view [:nav.related {:aria-label (shared/tr ui "related")}]
                   relations relation-groups)])

(defn entries-view
  "The homograph group `entries` as successive articles divided by
  horizontal rules, under the navigation state `nav`."
  [ui nav entries]
  (interpose [:hr.homograph] (map (partial entry-view ui nav) entries)))

(defn index-items
  "The linked contents of the sense index over `entries`, with the sense
  on screen per `nav` marked.

  Every entry heads its own numbered list of senses — the way back up
  to its headword and inflected forms — and the numbers match the
  sense numerals of the page. The entries of a homograph group divide
  by rules like the page, and the home entry of the marked sense is
  marked with it."
  [{:keys [spy]} entries]
  (->> (for [{:keys [file headword homographNumber senses]} entries]
         [:div {:replicant/key file}
          [hiccup/a (cond-> {:hiccup/entry file :class ["index-entry"]}
                      (and spy (some (comp #{spy} :id) senses))
                      (update :class conj "current"))
           headword (when homographNumber [:sup.hom homographNumber])]
          (into [:ol.index-senses]
                (map-indexed
                  (fn [i {:keys [id] :as sense}]
                    [:li (cond-> {:replicant/key (or id i)}
                           (= id spy) (assoc :class "current"))
                     (if id
                       [hiccup/a {:hiccup/entry file :hiccup/sense id}
                        (shared/sense-label sense)]
                       (shared/sense-label sense))])
                  senses))])
       (interpose [:hr.homograph])))

(defn indexable?
  "Does the group of `entries` have more than one sense to index?"
  [entries]
  (boolean (next (mapcat :senses entries))))

(defn index-panel
  "The sense index of the homograph group `entries` as a panel on the
  desk beside the page.

  The panel fades in as it spawns and out as it goes, so a move to a
  page without an index does not tear it off the desk.

  Nothing renders when the group is not `indexable?`."
  [ui nav entries]
  (when (indexable? entries)
    [:nav.sense-index {:replicant/key        :sense-index
                       :aria-label           (shared/tr ui "contents")
                       :replicant/mounting   {:class "arriving"}
                       :replicant/unmounting {:class "leaving"}}
     (index-items nav entries)]))

(defn index-disclosure
  "The sense index of the homograph group `entries` as a bordered
  disclosure that the entry content wraps around, for viewports
  without room for the panel.

  Nothing renders when the group is not `indexable?`."
  [ui nav entries]
  (when (indexable? entries)
    [:details.sense-index-inline
     [hiccup/tr {:hiccup/tag :summary} "contents"]
     [:nav {:aria-label (shared/tr ui "contents")}
      (index-items nav entries)]]))

(defn desk-aside
  "The panels beside the page on the desk: the sense index of the
  homograph group `entries`, the `prefs` under it as a second small
  sheet, and the `colophon` at the foot of the column.

  A zero-height sticky anchor at the top of the sheet carries the
  column, so it spawns level with the sheet and pins to the viewport
  top. The stylesheet shows the column only when the viewport has room
  for it beside the page, and shows the row under the search field
  instead when it does not.

  Both panels carry a key. The index comes and goes while the prefs
  stay, so without one Replicant matches the panels by position and
  rebuilds the prefs as the index arrives, which spends the fade of
  each on the other."
  [ui nav entries prefs colophon]
  [:div.desk-anchor
   [:div.desk-panels
    (index-panel ui nav entries)
    [:aside.prefs {:replicant/key :prefs
                   :aria-label    (shared/tr ui "preferences")}
     prefs]
    colophon]])

(defn related?
  "Does the presented `entry` or one of its senses carry relation rows?"
  [{:keys [relations relation-groups senses]}]
  (boolean (or relations relation-groups
               (some #(or (:relations %) (:relation-groups %)) senses))))

(defn language-select
  "The dropdown switching the UI language `lang` between the offered
  `languages`, defaulting to the resource language of the `manifest`
  when a table for it exists."
  [ui lang manifest languages]
  (let [value (or lang (:langCode manifest))
        value (if (some #{value} languages) value "en")]
    [:label.ui-language
     {:lang  (shared/en ui "interface")
      :title (shared/tr ui "The language of the interface.")}
     (shared/tr ui "interface") " "
     (into [:select
            {:on {:change [[:app/set-pref :lang "lang" :event/target.value]]}}]
           (for [code languages]
             [:option {:value code :selected (= code value)}
              (shared/language-name code)]))]))

(defn alpha-toggle
  "The checkbox forcing a strictly alphabetical order on the members of
  every relation row, over whatever order the dataset prefers."
  [ui alpha?]
  [:label.member-order
   {:lang  (shared/en ui "alphabetical")
    :title (shared/tr ui "Strictly alphabetical, without the dataset's ranking.")}
   [:input {:type    "checkbox"
            :checked alpha?
            :on      {:change [[:app/set-pref :alpha? "alpha"
                                :event/target.checked]]}}]
   " " (shared/tr ui "alphabetical")])

(defn presentation-toggle
  "The checkbox switching the presentation config of the dataset on and
  off, to compare an entry with the neutral default view."
  [ui presentation?]
  [:label.custom-view
   {:lang  (shared/en ui "custom")
    :title (shared/tr ui "The dataset's own presentation.")}
   [:input {:type    "checkbox"
            :checked presentation?
            :on      {:change [[:app/set-pref :presentation? "custom"
                                :event/target.checked]]}}]
   " " (shared/tr ui "custom")])

(defn matches
  "The first 100 rows of `index` whose headword begins with `query`."
  [index query]
  (let [q (str/lower-case query)]
    (into [] (comp (filter #(str/starts-with? (:lower %) q))
                   (take 100))
          index)))

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
  [ui rows query active]
  (list
    (let [s (if (empty? rows) "No matches" "matches: {n}")]
      [:p.result-count {:role  "status"
                        :lang  (shared/en ui s)
                        :class (when (seq rows) "visually-hidden")}
       (shared/tr ui s (count rows))])
    (when (seq rows)
      [:ol.results {:id "search-results" :role "listbox"
                    :aria-label (shared/tr ui "Search results")
                    :replicant/mounting {:class "arriving"}}
       (map-indexed
         (fn [i {:keys [headword file pos]}]
           [:li {:replicant/key file :role "none"}
            [hiccup/a {:hiccup/entry  file
                       :id            (str "result-" i)
                       :role          "option"
                       :aria-selected (if (= i active) "true" "false")}
             (result-headword headword query)
             (when (seq pos) [:i.pos pos])]])
         rows)])))

(defn search-view
  "The search result `rows` with the `active` row selected and the `query`
  prefix marked, or the `index-error` when the index failed to load.

  Nil `rows` mean the index is still loading."
  [ui rows index-error query active]
  (cond
    rows        (results-view ui rows query active)
    index-error (let [s "Search failed to load. Reload the page."]
                  [:p.error {:lang (shared/en ui s)} (shared/tr ui s)])))

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
        [:div [hiccup/tr {:hiccup/tag :dt} "publisher"] [:dd publisher]])
      (when license
        [:div [hiccup/tr {:hiccup/tag :dt} "licence"]
         [:dd (linked license (or licenseName license))]])
      (when rights
        [:div [hiccup/tr {:hiccup/tag :dt} "rights"] [:dd rights]])]
     (when (seq sources)
       [:section.titled {:aria-labelledby "front-matter-sources"}
        (let [s (if (some :license sources) "sources & licences" "sources")]
          [hiccup/tr {:hiccup/tag :h2.relation-group :id "front-matter-sources"} s])
        (into [:dl.labels]
              (map (fn [{:keys [title full uri license licenseName]}]
                     [:div
                      [:dt (linked uri (tagged title full))]
                      [:dd (linked license (or licenseName license))]]))
              sources)])]))

(defn footer-view
  "The colophon of the resource: the title, the URI, the language and
  the counts.

  The language of the content sits here with the rest of the resource
  metadata rather than beside the interface language, because it is a
  fact about the dataset and not a preference the reader can change.

  The title carries the link and the URI follows it as plain text, so
  the narrow column can drop the URI and still reach the resource."
  [{:keys [title uri langCode entries senses relations] :as manifest}]
  (when manifest
    (let [heading (or title "DMLex resource")]
      [:footer.colophon
       [:p.resource (if uri [:a {:href uri} heading] heading)
        (when uri [:span.uri uri])]
       [:dl.stats
        (when langCode
          [:div [hiccup/tr {:hiccup/tag :dt} "language"]
           [:dd {:lang langCode} (shared/language-name langCode)]])
        [:div [hiccup/tr {:hiccup/tag :dt} "entries"] [:dd entries]]
        [:div [hiccup/tr {:hiccup/tag :dt} "senses"] [:dd senses]]
        [:div [hiccup/tr {:hiccup/tag :dt} "relations"] [:dd relations]]]])))

(defn app
  "The root view over one value of the app state.

  Until the manifest or its error arrives, only the empty page sheet
  renders, so the English defaults never flash before the dataset's
  own front page. The search field and the result list form an ARIA
  combobox: focus stays in the field while aria-activedescendant
  points at the active option. The app replaces the markup of a
  pre-rendered page, so the field asks back for the focus that its
  autofocus gave it, unless a reveal is already claiming it."
  [{:keys [ui manifest presentation index index-error query active entries
           error languages lang nav folded alpha? presentation?]}]
  (if-not (or manifest error)
    [:div.container]
    (let [rows     (when (and index (seq query)) (matches index query))
          nav      (assoc nav :folded folded)
          prefs    (list
                     [:span.toggles
                      (when (some related? entries)
                        (alpha-toggle ui alpha?))
                      (when (and (seq entries)
                                 (seq (dissoc presentation "ui")))
                        (presentation-toggle ui presentation?))]
                     (language-select ui lang manifest languages))
          colophon (footer-view manifest)]
      [:div.container
       (desk-aside ui nav entries prefs colophon)
       [:search
        [:label.visually-hidden {:for  "search"
                                 :lang (shared/en ui "Search the dictionary")}
         (shared/tr ui "Search the dictionary")]
        [:input {:replicant/on-mount    (when-not (:reveal nav) [[:app/focus]])
                 :id                    "search"
                 :type                  "search"
                 :placeholder           (shared/tr ui "Type a word to look it up.")
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
                 :on                    {:input   [[:app/assoc
                                                    :query :event/target.value
                                                    :active nil]]
                                         :keydown [[:search/keydown]]}}]]
       [:div.controls prefs]
       [:main
        (when (or (seq query) (empty? entries))
          [:h1 {:id    "resource-title"
                :class (if (seq query) "visually-hidden" "resource-title")}
           (or (:title manifest) fallback-title)])
        (cond
          (seq query)   (search-view ui rows index-error query active)
          (seq entries) (list (index-disclosure ui nav entries)
                              (entries-view ui nav entries))
          error         [:p.error {:lang (shared/en ui "The page failed to load.")}
                         (shared/tr ui "The page failed to load.") " "
                         [hiccup/a {} (shared/tr ui "Go to the front page.")]]
          :else         (front-matter-view manifest))]
       colophon])))
