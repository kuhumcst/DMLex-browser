(ns dk.cst.dmlex-viewer.appledict
  "Convert a DMLex 1.0 JSON file, or a zip export containing one, into
  an Apple Dictionary source project.

  Renders the same resolved entry maps that dk.cst.dmlex-viewer.build
  writes for the web viewer as the d:dictionary XML of the Dictionary
  Development Kit, next to a stylesheet, an Info.plist and a Makefile.
  A Dublin Core metadata.json next to the DMLex file fills the bundle
  metadata and the front matter. Building the final .dictionary bundle
  requires the DDK from Apple's Additional Tools for Xcode: run
  `make && make install` in the output directory.

  The XML mixes a default XHTML namespace with d:-prefixed elements in
  the exact shape that the DDK scripts and WebKit expect, so the emitter
  is a small string-based hiccup renderer rather than clojure.data.xml,
  whose namespace-aware emission cannot reproduce that shape verbatim.

  Usage (from the project root, which anchors the stylesheet paths):
  clojure -J-Xmx8g -M:appledict <dmlex.json|zip> [<out-dir>] [<ddk-dir>]"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dmlex-viewer.build :as build]
            [dk.cst.dmlex-viewer.presentation :as presentation]
            [dk.cst.dmlex-viewer.shared :as shared]
            [dk.cst.dmlex-viewer.translations :as translations]))

;; -----------------------------------------------------------------------------
;; XML emission

(defn escape
  "Escape `s` for use as XML text content or an attribute value."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn xml-name
  "The XML name of the keyword `k`; its namespace becomes an XML prefix,
  e.g. :d/entry -> d:entry."
  [k]
  (if-let [prefix (namespace k)]
    (str prefix ":" (name k))
    (name k)))

(defn hiccup->xml
  "Render the hiccup `x` — nil, a string, a [tag attrs? & children] vector
  or a seq of hiccup — as an XML string.

  A nil child or attribute renders as nothing, and a childless element
  self-closes. Only a vector opening with a keyword is an element; any
  other sequential value is a seq of hiccup."
  [x]
  (cond
    (nil? x) ""
    (string? x) (escape x)
    (and (vector? x)
         (keyword? (first x))) (let [[tag & more] x
                                     [attrs children] (if (map? (first more))
                                                        [(first more) (rest more)]
                                                        [nil more])
                                     attrs' (str/join (for [[k v] attrs
                                                            :when (some? v)]
                                                        (str " " (xml-name k)
                                                             "=\"" (escape v) "\"")))]
                                 (if (empty? children)
                                   (str "<" (xml-name tag) attrs' "/>")
                                   (str "<" (xml-name tag) attrs' ">"
                                        (str/join (map hiccup->xml children))
                                        "</" (xml-name tag) ">")))
    (seqable? x) (str/join (map hiccup->xml x))
    :else (escape x)))

;; -----------------------------------------------------------------------------
;; Entry rendering, mirroring the views of dk.cst.dmlex-viewer.app

(defn tagged
  "The `tag` as a span with the `description` of the dataset as its tooltip."
  [tag description]
  (if description
    [:span {:title description} tag]
    tag))

(defn linked
  "The hiccup `x`, linked to `uri` when the dataset supplies one."
  [uri x]
  (if uri
    [:a {:href uri} x]
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
             "collocate" [:span {:class "collocate" :title lemma} text]
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

  Off the labels block the value loses its key column, so the tooltip
  opens with the type's display name, which the rename already puts in
  the export's language; assistive tech hears the same name. The dot
  separator lives in CSS, so it is never announced."
  [{:keys [tag description uri qualifier type display]}]
  (let [attr  (or display type)
        title (not-empty (str/join ": " (remove nil? [attr description])))]
    [:span {:class "inline-label"}
     (when attr [:span {:class "visually-hidden"} (str attr ": ")])
     (linked uri (tagged tag title))
     (when qualifier (str " (" qualifier ")"))]))

(defn labels-view
  "The `labels` as a definition list grouped by label type, with the extra
  `class` on the list and the chrome translated by the `ui` table."
  [ui class labels]
  (when (seq labels)
    [:dl {:class (str "labels " class) :d/priority "2"}
     (for [group (partition-by :type labels)
           :let [{:keys [type typeDescription typeUri display]} (first group)]]
       [:div {:data-type type}
        (if type
          [:dt (linked typeUri (tagged (or display type) typeDescription))]
          [:dt {:class "visually-hidden" :lang (shared/en ui "label")}
           (shared/tr ui "label")])
        (map label-dd group)])]))

(defn member-link
  "The x-dictionary link to the home entry of one relation member,
  targeting its home sense when the member is one.

  A sense link separates the #fragment from the entry id with an empty
  dictionary-id segment (r:<entry>:#<sense>): the Dictionary Development
  Kit indexes referred entries by splitting the reference at the first
  colon, and a bare #fragment would keep the entry out of the reference
  index, leaving the link dead. Dictionary.app drops the fragment before
  resolving the entry and scrolls to it afterwards."
  [{:keys [headword file sense indicator]}]
  [:a {:href  (str "x-dictionary:r:" file (when sense (str ":#" sense)))
       :title indicator}
   headword])

(defn members-dd
  "The `members` of one relation row, with `ui` translating the chrome.

  A long row folds behind a details disclosure."
  [ui members]
  (let [links (interpose ", " (map member-link members))]
    (if (> (count members) 10)
      [:dd
       [:details
        ;; The text lives in CSS content so that Dictionary.app cannot
        ;; look it up on click.
        [:summary {:lang       (shared/en ui "{n} entries")
                   :data-count (count members)} ""]
        [:p {:class "member-list"} links]]]
      [:dd links])))

(defn relations-dl
  "The pre-resolved `relations` rows as a definition list: the role of the
  related senses against the links to their entries."
  [ui relations]
  (when (seq relations)
    [:dl {:class "relations" :d/priority "2"}
     (for [{:keys [type role description roleDescription note uri display
                   display-role members]}
           relations]
       [:div {:data-type type :data-role role}
        [:dt {:title (or note roleDescription description type)}
         (linked uri (or display-role role display type))]
        (members-dd ui members)])]))

(defn relations-view
  "The `relations` rows — or the titled `relation-groups` of the
  presentation config — as the related-entries block.

  Each group renders as a section under its headline, with the group's
  description as the headline's tooltip."
  [ui relations relation-groups]
  (if (seq relation-groups)
    (for [{:keys [title description relations]} relation-groups]
      [:div {:class      (str "relation-section" (when title " titled"))
             :d/priority "2"}
       (when title
         ;; Header text lives in CSS content so that Dictionary.app
         ;; cannot look it up on click; likewise the paradigm rows.
         [:h2 {:class      "relation-group"
               :title      description
               :data-label title} ""])
       (relations-dl ui relations)])
    (when (seq relations)
      [:div {:class "relation-section titled" :d/priority "2"}
       [:h2 {:class      "relation-group"
             :lang       (shared/en ui "related")
             :data-label (shared/tr ui "related")} ""]
       (relations-dl ui relations)])))

(defn translations-view
  "The headword `translations` of one sense as a definition list grouped
  by language: the language code against its comma-joined equivalents."
  [translations]
  (when (seq translations)
    [:dl {:class "labels translations" :d/priority "2"}
     (for [lang (distinct (map :lang translations))]
       [:div
        [:dt lang]
        [:dd {:lang lang}
         (str/join ", " (keep #(when (= lang (:lang %)) (:text %))
                              translations))]])]))

(defn example-view
  "One example as a paragraph, or as a cited quotation when it carries a
  source.

  The labels and the citation sit outside the quoted text, which is all
  a blockquote may contain."
  [{:keys [text runs labels source sourceDescription sourceUri
           sourceElaboration]}]
  (let [example (runs-view text runs)
        labels' (when (seq labels)
                  [:span {:class "example-labels"} " ("
                   (interpose ", " (map (fn [{:keys [tag description uri]}]
                                          (linked uri (tagged tag description)))
                                        labels))
                   ")"])]
    (if source
      [:figure {:class "example" :d/priority "2"}
       [:blockquote [:p example]]
       labels'
       [:figcaption
        [:cite (linked sourceUri
                       (tagged source
                               (not-empty
                                 (str/join " " (remove nil? [sourceDescription
                                                             sourceElaboration])))))]]]
      [:p {:class "example" :d/priority "2"} example labels'])))

(defn sense-view
  "One sense as a list item: the indicator, the definitions, the
  examples, the labels, the translations and the relations.

  The sense id becomes the anchor that sense-targeted member links
  scroll to."
  [ui {:keys [id indicator definitions translations examples labels relations
              relation-groups]}]
  [:li (cond-> {:class "sense"}
         id (assoc :id id))
   [:p {:class "meaning"}
    (when indicator [:span {:class "indicator"} indicator])
    [:span {:class "definitions"}
     (interpose "; " (map (fn [{:keys [text type typeDescription runs]}]
                            [:span {:class     "definition"
                                    :data-type type
                                    :title     typeDescription}
                             (runs-view text runs)])
                          definitions))]]
   (map example-view examples)
   (labels-view ui "sense-labels" labels)
   (translations-view translations)
   (relations-view ui relations relation-groups)])

(defn inflections-view
  "The inflected `forms` of `headword` as one run-in definition list of
  short forms, with the paradigm slot visually hidden and as a hover
  title.

  One representative per paradigm slot — the form with a reduced short
  when the slot has one — so variant spellings stay in the paradigm, as
  does a form spelled like the headword."
  [ui headword forms]
  (when-let [forms (->> (partition-by #(or (:description %) (:tag %) (:text %))
                                      forms)
                        (map (fn [group]
                               (or (first (filter :short group))
                                   (first group))))
                        (remove #(= headword (:text %)))
                        (seq))]
    [:dl {:class "inflections" :d/priority "2"}
     (for [{:keys [tag text short description labels]}
           (shared/distinct-by #(or (:short %) (:text %)) forms)]
       [:div
        [:dt {:class "visually-hidden"}
         (or description tag (shared/tr ui "form"))]
        [:dd {:title (if short
                       (str text (when description
                                   (str " — " description)))
                       description)}
         (or short text)
         (when (seq labels)
           [:span {:class "form-label"}
            (str " (" (str/join ", " (map :tag labels)) ")")])]])]))

(defn paradigm-view
  "The full paradigm of the inflected `forms` as a table behind a details
  disclosure.

  One row per paradigm slot; forms that share the slot — variant
  spellings — join on the row."
  [ui forms]
  (when (some #(or (:tag %) (:description %)) forms)
    [:details {:class "paradigm" :d/priority "2"}
     [:summary {:lang (shared/en ui "all forms") :class "all-forms"} ""]
     [:table
      [:caption {:class "visually-hidden" :lang (shared/en ui "all forms")}
       (shared/tr ui "all forms")]
      [:tbody
       (for [group (partition-by #(or (:description %) (:tag %)) forms)
             :let [{:keys [tag description]} (first group)]]
         [:tr
          [:th {:scope "row" :data-label (or description tag)} ""]
          [:td (interpose ", "
                          (map (fn [{:keys [text labels]}]
                                 (list text
                                       (when (seq labels)
                                         [:span {:class "form-label"}
                                          (str " (" (str/join ", " (map :tag labels)) ")")])))
                               group))]])]]]))

(defn ->index
  "The d:index terms of one entry: the `headword` plus every distinct full
  inflected form in `forms`.

  The extra terms redirect to the headword."
  [headword forms]
  (cons [:d/index {:d/value headword}]
        (for [text (distinct (map :text forms))
              :when (not= text headword)]
          [:d/index {:d/value text :d/title (str text " (" headword ")")}])))

(defn ->entry
  "One d:entry of the resolved `entry` map of dk.cst.dmlex-viewer.build:
  the index terms, the header, the senses and the entry-level relations.

  Everything but the headword, the pos and the definitions carries
  d:priority 2, which the compact Look Up panel omits."
  [ui {:keys [file headword homographNumber partsOfSpeech labels inline-labels
              inflectedForms senses relations relation-groups]}]
  [:d/entry {:id file :d/title headword}
   (->index headword inflectedForms)
   [:h1 {:class "headword"} [:dfn headword]
    (when homographNumber [:sup {:class "hom"} homographNumber])]
   (when (or (seq partsOfSpeech) (seq inline-labels))
     [:p {:class "pos"}
      (when (seq partsOfSpeech)
        [:span {:class "pos-list"}
         (interpose ", " (map (fn [{:keys [tag description uri]}]
                                (linked uri (tagged (or description tag)
                                                    (when description tag))))
                              partsOfSpeech))])
      (map inline-label-view inline-labels)])
   (inflections-view ui headword inflectedForms)
   (paradigm-view ui inflectedForms)
   (when (seq labels)
     [:div {:class "labels-section titled" :d/priority "2"}
      [:h2 {:class      "relation-group"
            :lang       (shared/en ui "about the word")
            :data-label (shared/tr ui "about the word")} ""]
      (labels-view ui "entry-labels" labels)])
   [:ol {:class (str "senses" (when (= 1 (count senses)) " single"))}
    (map (partial sense-view ui) senses)]
   (relations-view ui relations relation-groups)])

;; -----------------------------------------------------------------------------
;; Bundle metadata

(defn about-title
  "The About title of the bundle `title`, translated by the `ui` table."
  [ui title]
  (str/replace (shared/tr ui "About {title}") "{title}" title))

(defn bundle-info
  "The bundle identity of the export: the display fields of the DMLex
  `resource` merged with its Dublin Core `metadata`."
  [{:keys [title uri langCode]} metadata]
  (let [title (or (get metadata "dc:title") title "DMLex dictionary")
        lang  (or (get metadata "dc:language") langCode "en")]
    {:title       title
     :identifier  (str "dmlex."
                       (-> (str/lower-case title)
                           (str/replace #"[^a-z0-9]+" "-")
                           (str/replace #"^-+|-+$" ""))
                       ".dictionary")
     :version     (or (get metadata "dc:issued") "1.0")
     :lang        lang
     :uri         (or (get metadata "dc:identifier") uri)
     :description (build/localized (get metadata "dc:description") lang)
     :publisher   (get metadata "dc:publisher")
     :rights      (get metadata "dc:rights")
     :license     (get metadata "dc:license")
     :licenseName (build/license-name (get metadata "dc:license"))
     :sources     (mapv build/->source (get metadata "dc:source"))}))

(defn front-matter
  "The front matter d:entry assembled from the bundle `info`: the title,
  the description, the rights, the sources and the home URI.

  Info.plist points at it via DCSDictionaryFrontMatterReferenceID."
  [ui {:keys [title description rights license licenseName sources uri]}]
  [:d/entry {:id "front_back_matter" :d/title (about-title ui title)}
   [:d/index {:d/value title}]
   [:div {:class "front-matter"}
    [:h1 title]
    (when description [:p description])
    (when rights [:p rights])
    (when license [:p [:a {:href license} (or licenseName license)]])
    (when (seq sources)
      [:ul (for [{:keys [title full uri license licenseName]} sources]
             [:li (linked uri (tagged title full))
              (when license
                (list " · " [:a {:href license} (or licenseName license)]))])])
    (when uri [:p [:a {:href uri} uri]])]])

;; -----------------------------------------------------------------------------
;; Project files

(def xml-preamble
  "The XML declaration and the d:dictionary root element, opening the
  default XHTML namespace and the d: dictionary-service namespace."
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<d:dictionary xmlns=\"http://www.w3.org/1999/xhtml\" "
       "xmlns:d=\"http://www.apple.com/DTDs/DictionaryService-1.0.rng\">\n"))

(defn front-matter-xml
  "The front matter as XML: the dataset's own `front` XHTML fragment when
  it ships one, else the generic assembly from the bundle `info`."
  [ui info front]
  (if front
    (str "<d:entry id=\"front_back_matter\" d:title=\""
         (escape (about-title ui (:title info))) "\">"
         (hiccup->xml [:d/index {:d/value (:title info)}])
         front
         "</d:entry>")
    (hiccup->xml (front-matter ui info))))

(defn drop-first-sense-anchors
  "Match the web viewer's arrival rule in the resolved display `entry`:
  a cross-entry member link to the first sense of its target opens the
  entry from the top, headword in view, so its anchor is dropped; a
  same-entry link keeps its anchor and scrolls. `first-sense?` answers
  whether a sense id names the first sense of its home entry."
  [first-sense? {:keys [file] :as entry}]
  (let [member* (fn [{:keys [sense] :as m}]
                  (cond-> m
                    (and sense
                         (not= (:file m) file)
                         (first-sense? sense))
                    (dissoc :sense)))
        rows*   (fn [rows]
                  (mapv #(update % :members (partial mapv member*)) rows))
        sense*  (fn [s]
                  (cond-> s (:relations s) (update :relations rows*)))]
    (cond-> entry
      (:relations entry) (update :relations rows*)
      (:senses entry)    (update :senses (partial mapv sense*)))))

(defn write-xml!
  "Stream the d:dictionary XML of the DMLex `resource` to `file`, with
  the presentation `config` applied to every entry.

  The stream opens with the front matter of the bundle `info` — the
  dataset's own `front` fragment when it ships one."
  [file info config front resource]
  (let [env          (build/->env resource)
        ui           (get config "ui")
        first-sense? (into #{}
                           (keep (comp :id first :senses))
                           (:entries resource))
        collate      (when (= "collation" (get config "memberOrder"))
                       (let [collator (build/->collator (:langCode resource))]
                         (shared/member-order
                           (fn [a b] (.compare collator a b)))))
        present      (fn [entry]
                       (cond->> (presentation/present-entry config entry)
                         collate (presentation/collate-members collate)))
        entry-xml    (fn [entry]
                       (->> (build/->entry-file env entry)
                            (drop-first-sense-anchors first-sense?)
                            (present)
                            (->entry ui)
                            (hiccup->xml)))]
    (with-open [w (io/writer file)]
      (.write w xml-preamble)
      (.write w (front-matter-xml ui info front))
      (.write w "\n")
      (doseq [entry (:entries resource)]
        (.write w (entry-xml entry))
        (.write w "\n"))
      (.write w "</d:dictionary>\n"))))

(defn info-plist
  "The Info.plist XML of the dictionary bundle described by `info`."
  [{:keys [title identifier version lang publisher rights]}]
  (str
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
    " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
    "<plist version=\"1.0\">\n<dict>\n"
    "\t<key>CFBundleDisplayName</key>\n\t<string>" (escape title) "</string>\n"
    "\t<key>CFBundleIdentifier</key>\n\t<string>" (escape identifier) "</string>\n"
    "\t<key>CFBundleName</key>\n\t<string>" (escape title) "</string>\n"
    "\t<key>CFBundleShortVersionString</key>\n\t<string>" (escape version) "</string>\n"
    (when rights
      (str "\t<key>DCSDictionaryCopyright</key>\n\t<string>"
           (escape rights) "</string>\n"))
    (when publisher
      (str "\t<key>DCSDictionaryManufacturerName</key>\n\t<string>"
           (escape publisher) "</string>\n"))
    "\t<key>DCSDictionaryFrontMatterReferenceID</key>\n"
    "\t<string>front_back_matter</string>\n"
    "\t<key>DCSDictionaryLanguages</key>\n\t<array>\n\t\t<dict>\n"
    "\t\t\t<key>DCSDictionaryDescriptionLanguage</key>\n"
    "\t\t\t<string>" (escape lang) "</string>\n"
    "\t\t\t<key>DCSDictionaryIndexLanguage</key>\n"
    "\t\t\t<string>" (escape lang) "</string>\n"
    "\t\t</dict>\n\t</array>\n"
    "</dict>\n</plist>\n"))

(defn makefile
  "The Makefile of the export directory: build the `title` dictionary with
  the DDK in `ddk-dir`.

  Variable values stay unquoted and every use site quotes, so a path
  with spaces works both here and in a command-line override like
  make DICT_BUILD_TOOL_DIR=..."
  [title ddk-dir]
  (str "DICT_NAME\t\t=\t" title "\n"
       "DICT_SRC_PATH\t\t=\tDictionary.xml\n"
       "CSS_PATH\t\t=\tDictionary.css\n"
       "PLIST_PATH\t\t=\tInfo.plist\n"
       "DICT_BUILD_OPTS\t\t=\n"
       "DICT_BUILD_TOOL_DIR\t=\t" ddk-dir "\n"
       "DICT_BUILD_TOOL_BIN\t=\t$(DICT_BUILD_TOOL_DIR)/bin\n"
       "DICT_DEV_KIT_OBJ_DIR\t=\t./objects\n"
       "export\tDICT_DEV_KIT_OBJ_DIR\n"
       "DESTINATION_FOLDER\t=\t$(HOME)/Library/Dictionaries\n"
       "\n"
       "all:\n"
       "\t\"$(DICT_BUILD_TOOL_BIN)/build_dict.sh\" $(DICT_BUILD_OPTS)"
       " \"$(DICT_NAME)\" \"$(DICT_SRC_PATH)\" \"$(CSS_PATH)\" \"$(PLIST_PATH)\"\n"
       "\techo \"Done.\"\n"
       "\n"
       "install:\n"
       "\tmkdir -p \"$(DESTINATION_FOLDER)\"\n"
       "\tditto --noextattr --norsrc \"$(DICT_DEV_KIT_OBJ_DIR)/$(DICT_NAME).dictionary\""
       " \"$(DESTINATION_FOLDER)/$(DICT_NAME).dictionary\"\n"
       "\ttouch \"$(DESTINATION_FOLDER)\"\n"
       "\techo \"Done.\"\n"
       "\n"
       "clean:\n"
       "\t/bin/rm -rf \"$(DICT_DEV_KIT_OBJ_DIR)\"\n"))

(def css-files
  "The base stylesheet of the bundle: the shared tokens, then the
  Dictionary.app rules.

  Paths are relative to the project root."
  ["public/css/tokens.css"
   "resources/appledict/style.css"])

(defn chrome-css
  "CSS overriding the English strings the base stylesheet renders as
  content, with their translations in the `ui` table, or nil.

  The count template must open with {n}, which maps onto the
  data-count attribute."
  [ui]
  (let [entries (get ui "{n} entries")
        rules   (remove nil?
                        [(when-let [s (get ui "all forms")]
                           (str "summary.all-forms::after { content: \""
                                s "\"; }"))
                         (when (and entries (str/starts-with? entries "{n}"))
                           (str ".relations summary::after"
                                " { content: attr(data-count) \""
                                (subs entries 3) "\"; }"))])]
    (when (seq rules)
      (str/join "\n" rules))))

(defn stylesheet
  "The full stylesheet of the bundle: the base `css-files`, the dataset
  stylesheets the `config` names read through `content-of`, and the
  translated chrome strings.

  The shared \"css\" hook comes first, then the appledict-specific one."
  [content-of config]
  (->> (concat (->> (map io/file css-files)
                    (filter #(.exists %))
                    (map slurp))
               (->> [(get config "css")
                     (get-in config ["appledict" "css"])]
                    (remove nil?)
                    (keep content-of))
               (some-> (chrome-css (get config "ui")) (vector)))
       (str/join "\n")))

(def ddk-default
  "The location where Apple's installer puts the Dictionary Development
  Kit."
  "/Library/Developer/Extras/Dictionary Development Kit")

(defn export!
  "Convert the DMLex JSON file (or zip export) `in` into an Apple
  Dictionary source project in `out`, with a Makefile for `ddk-dir`.

  A presentation.json next to `in` shapes the entries; its \"appledict\"
  section can override the bundle identifier and add a stylesheet and a
  front-matter fragment."
  [in out ddk-dir]
  (println "Reading" in)
  (let [{:keys [dmlex-file content-of]} (build/->input in)
        resource (json/read-str (content-of dmlex-file) :key-fn keyword)
        config   (build/read-config content-of)
        ui       (merge (get (translations/tables) (:langCode resource))
                        (get config "ui"))
        config   (cond-> config (seq ui) (assoc "ui" ui))
        metadata (build/read-companion content-of "metadata.json")
        info     (cond-> (bundle-info resource metadata)
                   (get-in config ["appledict" "identifier"])
                   (assoc :identifier (get-in config ["appledict" "identifier"])))
        front    (some-> (get-in config ["appledict" "frontMatter"])
                         (content-of))
        xml-file (io/file out "Dictionary.xml")]
    (io/make-parents xml-file)
    (println "Writing" (count (:entries resource)) "entries into" (str out))
    (write-xml! xml-file info config front resource)
    (spit (io/file out "Dictionary.css") (stylesheet content-of config))
    (spit (io/file out "Info.plist") (info-plist info))
    (spit (io/file out "Makefile") (makefile (:title info) ddk-dir))
    (println "Done. Build the bundle with `make && make install` in" out)))

(defn -main
  "Export the Apple Dictionary source project from the command-line
  arguments `in`, `out` and `ddk-dir`."
  [& [in out ddk-dir]]
  (if in
    (export! in (or out "export/appledict") (or ddk-dir ddk-default))
    (println (str "Usage: clojure -J-Xmx8g -M:appledict"
                  " <dmlex.json|zip> [<out-dir>] [<ddk-dir>]")))
  (shutdown-agents))

(comment
  (export! "datasets/example-dmlex.json" "export/appledict" ddk-default)
  #_.)
