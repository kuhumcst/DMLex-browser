(ns dk.cst.dmlex-viewer.appledict
  "Convert a DMLex 1.0 JSON file into an Apple Dictionary source project.

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
  clojure -J-Xmx8g -M:appledict <dmlex.json> [<out-dir>] [<ddk-dir>]"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dmlex-viewer.build :as build]
            [dk.cst.dmlex-viewer.shared :as shared]))

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
  "Render the hiccup `x` — nil, a string, a [tag attrs? & children] vector or
  a seq of hiccup — as an XML string. A nil child or attribute renders as
  nothing, and a childless element self-closes. Only a vector opening with a
  keyword is an element; any other sequential value is a seq of hiccup."
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
  "The `tag` as an abbr when the dataset supplies a `description` for it."
  [tag description]
  (if description
    [:abbr {:title description} tag]
    tag))

(defn label-dd
  "The dd of one label: its tag, linked when the label carries a URI."
  [{:keys [tag description uri]}]
  [:dd (if uri
         [:a {:href uri} (tagged tag description)]
         (tagged tag description))])

(defn labels-view
  "The `labels` as a definition list grouped by label type, with the extra
  `class` on the list."
  [class labels]
  (when (seq labels)
    [:dl {:class (str "labels " class) :d/priority "2"}
     (for [group (partition-by :type labels)
           :let [{:keys [type typeDescription]} (first group)]]
       [:div
        (if type
          [:dt (tagged type typeDescription)]
          [:dt {:class "visually-hidden" :lang "en"} "label"])
        (map label-dd group)])]))

(defn member-link
  "The x-dictionary link to the home entry of one relation member."
  [{:keys [headword file indicator]}]
  [:a {:href (str "x-dictionary:r:" file) :title indicator} headword])

(defn members-dd
  "The `members` of one relation row, folded behind a details disclosure when
  the row is long."
  [members]
  (let [links (interpose ", " (map member-link members))]
    (if (> (count members) 10)
      [:dd
       [:details
        [:summary {:lang "en"} (str (count members) " entries")]
        [:p {:class "member-list"} links]]]
      [:dd links])))

(defn relations-view
  "The pre-resolved `relations` rows as a definition list: the role of the
  related senses against the links to their entries."
  [relations]
  (when (seq relations)
    [:dl {:class "relations" :d/priority "2"}
     (for [{:keys [type role description members]} relations]
       [:div
        [:dt {:title (or description type)} (or role type)]
        (members-dd members)])]))

(defn example-view
  "One example as a quotation with its source citation."
  [{:keys [text source sourceDescription sourceElaboration]}]
  [:blockquote {:class "example" :d/priority "2"}
   [:p text]
   (when source
     [:footer
      [:cite (tagged source
                     (not-empty
                       (str/join " " (remove nil? [sourceDescription
                                                   sourceElaboration]))))]])])

(defn sense-view
  "One sense as a list item: the indicator, the definitions, the examples,
  the labels and the relations."
  [{:keys [indicator definitions examples labels relations]}]
  [:li {:class "sense"}
   [:p {:class "meaning"}
    (when indicator
      (list [:i {:class "indicator"} indicator] [:span {:class "sep"} "|"]))
    [:span {:class "definitions"}
     (interpose "; " (map :text definitions))]]
   (map example-view examples)
   (labels-view "sense-labels" labels)
   (relations-view relations)])

(defn inflections-view
  "The inflected `forms` of `headword` as one run-in definition list of
  short forms, with the paradigm slot visually hidden and as a hover title.
  A form spelled like the headword stays out; the paradigm keeps it."
  [headword forms]
  (when-let [forms (seq (remove #(= headword (:text %)) forms))]
    [:dl {:class "inflections" :d/priority "2"}
     (for [{:keys [tag text short description labels]}
           (shared/distinct-by #(or (:short %) (:text %)) forms)]
       [:div
        [:dt {:class "visually-hidden"} (or description tag "form")]
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
  disclosure: one row per form, the paradigm slot as the row header."
  [forms]
  (when (some #(or (:tag %) (:description %)) forms)
    [:details {:class "paradigm" :d/priority "2"}
     [:summary {:lang "en"} "all forms"]
     [:table
      [:tbody
       (for [{:keys [tag text description labels]} forms]
         [:tr
          [:th {:scope "row"} (or description tag)]
          [:td text
           (when (seq labels)
             [:span {:class "form-label"}
              (str " (" (str/join ", " (map :tag labels)) ")")])]])]]]))

(defn ->index
  "The d:index terms of one entry: the `headword` plus every distinct full
  inflected form in `forms`, which redirects to the headword."
  [headword forms]
  (cons [:d/index {:d/value headword}]
        (for [text (distinct (map :text forms))
              :when (not= text headword)]
          [:d/index {:d/value text :d/title (str text " (" headword ")")}])))

(defn ->entry
  "One d:entry of the resolved `entry` map of dk.cst.dmlex-viewer.build:
  the index terms, the header, the senses and the entry-level relations."
  [{:keys [file headword homographNumber partsOfSpeech labels inflectedForms
           senses relations]}]
  [:d/entry {:id file :d/title headword}
   (->index headword inflectedForms)
   [:h1 {:class "headword"} [:dfn headword]
    (when homographNumber [:sup {:class "hom"} homographNumber])]
   (when (seq partsOfSpeech)
     [:p {:class "pos"}
      (interpose ", " (map (fn [{:keys [tag description]}]
                             (tagged tag description))
                           partsOfSpeech))])
   (inflections-view headword inflectedForms)
   (paradigm-view inflectedForms)
   (labels-view "entry-labels" labels)
   [:ol {:class (str "senses" (when (= 1 (count senses)) " single"))}
    (map sense-view senses)]
   (relations-view relations)])

;; -----------------------------------------------------------------------------
;; Bundle metadata

(defn read-metadata
  "The Dublin Core metadata.json next to the DMLex file `in`, or nil."
  [in]
  (let [f (io/file (or (.getParent (io/file in)) ".") "metadata.json")]
    (when (.exists f)
      (json/read-str (slurp f)))))

(defn localized
  "The string `s` itself, or the entry of `lang` (falling back to English,
  then to anything) when `s` is a language-keyed map."
  [s lang]
  (if (map? s)
    (or (get s lang) (get s "en") (first (vals s)))
    s))

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
     :description (localized (get metadata "dc:description") lang)
     :publisher   (get metadata "dc:publisher")
     :rights      (get metadata "dc:rights")
     :license     (get metadata "dc:license")
     :sources     (get metadata "dc:source")}))

(defn front-matter
  "The front matter d:entry assembled from the bundle `info`: the title,
  the description, the rights, the sources and the home URI. Info.plist
  points at it via DCSDictionaryFrontMatterReferenceID."
  [{:keys [title description rights license sources uri]}]
  [:d/entry {:id "front_back_matter" :d/title (str "About " title)}
   [:d/index {:d/value title}]
   [:div {:class "front-matter"}
    [:h1 title]
    (when description [:p description])
    (when rights [:p rights])
    (when license [:p [:a {:href license} license]])
    (when (seq sources)
      [:ul (for [source sources]
             [:li (get source "dc:title")
              (when-let [url (get source "dc:license")]
                (list " · " [:a {:href url} url]))])])
    (when uri [:p [:a {:href uri} uri]])]])

;; -----------------------------------------------------------------------------
;; Project files

(def xml-preamble
  "The XML declaration and the d:dictionary root element, opening the
  default XHTML namespace and the d: dictionary-service namespace."
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<d:dictionary xmlns=\"http://www.w3.org/1999/xhtml\" "
       "xmlns:d=\"http://www.apple.com/DTDs/DictionaryService-1.0.rng\">\n"))

(defn write-xml!
  "Stream the d:dictionary XML of the DMLex `resource` to `file`, opening
  with the front matter of the bundle `info`."
  [file info resource]
  (let [env (build/->env resource)]
    (with-open [w (io/writer file)]
      (.write w xml-preamble)
      (.write w (hiccup->xml (front-matter info)))
      (.write w "\n")
      (doseq [entry (:entries resource)]
        (.write w (hiccup->xml (->entry (build/->entry-file env entry))))
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
  the DDK in `ddk-dir`."
  [title ddk-dir]
  (str "DICT_NAME\t\t=\t\"" title "\"\n"
       "DICT_SRC_PATH\t\t=\tDictionary.xml\n"
       "CSS_PATH\t\t=\tDictionary.css\n"
       "PLIST_PATH\t\t=\tInfo.plist\n"
       "DICT_BUILD_OPTS\t\t=\n"
       "DICT_BUILD_TOOL_DIR\t=\t\"" ddk-dir "\"\n"
       "DICT_BUILD_TOOL_BIN\t=\t\"$(DICT_BUILD_TOOL_DIR)/bin\"\n"
       "DICT_DEV_KIT_OBJ_DIR\t=\t./objects\n"
       "export\tDICT_DEV_KIT_OBJ_DIR\n"
       "DESTINATION_FOLDER\t=\t~/Library/Dictionaries\n"
       "\n"
       "all:\n"
       "\t\"$(DICT_BUILD_TOOL_BIN)/build_dict.sh\" $(DICT_BUILD_OPTS)"
       " $(DICT_NAME) $(DICT_SRC_PATH) $(CSS_PATH) $(PLIST_PATH)\n"
       "\techo \"Done.\"\n"
       "\n"
       "install:\n"
       "\tmkdir -p $(DESTINATION_FOLDER)\n"
       "\tditto --noextattr --norsrc $(DICT_DEV_KIT_OBJ_DIR)/$(DICT_NAME).dictionary"
       " $(DESTINATION_FOLDER)/$(DICT_NAME).dictionary\n"
       "\ttouch $(DESTINATION_FOLDER)\n"
       "\techo \"Done.\"\n"
       "\n"
       "clean:\n"
       "\t/bin/rm -rf $(DICT_DEV_KIT_OBJ_DIR)\n"))

(def css-files
  "The stylesheet of the bundle: the shared tokens, then the Dictionary.app
  rules. Paths are relative to the project root."
  ["public/css/tokens.css"
   "resources/appledict/style.css"])

(def ddk-default
  "The location where Apple's installer puts the Dictionary Development
  Kit."
  "/Library/Developer/Extras/Dictionary Development Kit")

(defn export!
  "Convert the DMLex JSON file `in` into an Apple Dictionary source project
  in the directory `out`, with a Makefile pointing at the DDK in `ddk-dir`."
  [in out ddk-dir]
  (println "Reading" in)
  (let [resource (json/read-str (slurp in) :key-fn keyword)
        info     (bundle-info resource (read-metadata in))
        xml-file (io/file out "Dictionary.xml")]
    (io/make-parents xml-file)
    (println "Writing" (count (:entries resource)) "entries into" (str out))
    (write-xml! xml-file info resource)
    (spit (io/file out "Dictionary.css") (str/join "\n" (map slurp css-files)))
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
                  " <dmlex.json> [<out-dir>] [<ddk-dir>]")))
  (shutdown-agents))

(comment
  (export! "datasets/example-dmlex.json" "export/appledict" ddk-default)
  #_.)
