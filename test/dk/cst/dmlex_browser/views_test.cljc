(ns dk.cst.dmlex-browser.views-test
  "Tests of the pure views, through the HTML that Replicant renders from
  them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-browser.shared :as shared]
            [dk.cst.dmlex-browser.views :as views]
            [replicant.string :as replicant]))

(def index
  [{:headword "Abe" :lower "abe" :file "abe"}
   {:headword "abekat" :lower "abekat" :file "abekat"}
   {:headword "bavian" :lower "bavian" :file "bavian"}])

(defn html
  "The hiccup `x` rendered to HTML under the UI table `ui`, English by
  default."
  ([x] (html nil x))
  ([ui x] (replicant/render x {:alias-data {:ui ui}})))

(deftest matches-test
  (testing "a case-insensitive prefix filter"
    (is (= ["Abe" "abekat"] (map :headword (views/matches index "AB")))))
  (testing "no matches"
    (is (= [] (views/matches index "xyz")))))

(deftest distinct-by-test
  (is (= [{:x 1 :y :a} {:x 2 :y :c}]
         (shared/distinct-by :x [{:x 1 :y :a} {:x 1 :y :b} {:x 2 :y :c}]))))

(deftest tr-test
  (testing "the English string is its own key and its own fallback"
    (is (= "alle former" (shared/tr {"all forms" "alle former"} "all forms")))
    (is (= "all forms" (shared/tr nil "all forms"))))
  (testing "a count fills the {n} placeholder"
    (is (= "3 ord" (shared/tr {"{n} entries" "{n} ord"} "{n} entries" 3)))
    (is (= "3 entries" (shared/tr nil "{n} entries" 3))))
  (testing "the lang attribute marks only untranslated strings"
    (is (= "en" (shared/en nil "all forms")))
    (is (nil? (shared/en {"all forms" "alle former"} "all forms")))))

(deftest translated-chrome-test
  (let [ui       {"entries" "opslagsord" "senses" "betydninger"
                  "relations" "relationer"}
        rendered (html ui (views/footer-view {:title   "T"
                                              :entries 1 :senses 2 :relations 3}))]
    (testing "translated chrome renders in the dataset's language"
      (is (str/includes? rendered "opslagsord")))
    (testing "a translated string drops its English lang marker"
      (is (not (str/includes? rendered "lang=\"en\"")))))
  (testing "an untranslated string keeps its marker"
    (is (str/includes? (html (views/footer-view {:title "T" :entries 1}))
                       "<dt lang=\"en\">entries</dt>"))))

(deftest footer-language-test
  (testing "the language of the content is metadata, not a preference"
    (let [rendered (html (views/footer-view {:title "T" :langCode "da"}))]
      (is (str/includes? rendered "<dd lang=\"da\">dansk</dd>"))))
  (testing "a resource without a language says nothing"
    (is (not (str/includes? (html (views/footer-view {:title "T"}))
                            "<dd lang=")))))

(deftest runs-view-test
  (testing "marker runs render the marked headword in bold"
    (is (= (list "en stor " [:b "hund"])
           (views/runs-view "en stor hund"
                            [{:text "en stor "}
                             {:text "hund" :marker "headword"}]))))
  (testing "text without runs renders plain"
    (is (= "en stor hund" (views/runs-view "en stor hund" nil)))))

(deftest result-headword-test
  (testing "the completion after the matched prefix is emphasised"
    (is (= (list "ab" [:b "ekat"])
           (views/result-headword "abekat" "ab"))))
  (testing "an exact match carries no emphasis"
    (is (= "abe" (views/result-headword "abe" "abe"))))
  (testing "a query longer than the headword marks nothing"
    (is (= "abe" (views/result-headword "abe" "abekat")))))

(deftest inflections-view-test
  (testing "a form spelled like the headword stays out of the line"
    (is (nil? (views/inflections-view "år" [{:text "år"}])))
    (is (= 1 (count (rest (views/inflections-view
                            "år" [{:text "år"}
                                  {:text "årene" :short "-ene"}]))))))
  (testing "the reduced form represents its slot; the variant stays out"
    (is (= 1 (count (rest (views/inflections-view
                            "hund" [{:tag "111" :text "hunden" :short "-en"}
                                    {:tag "111" :text "hund-en"}])))))))

(deftest entries-view-test
  (testing "a homograph group renders as articles divided by rules"
    (let [rendered (html (views/entries-view nil {} [{:file "a" :senses []}
                                                     {:file "b" :senses []}]))]
      (is (= 1 (count (re-seq #"<hr" rendered))))
      (is (str/includes? rendered "id=\"a\""))
      (is (str/includes? rendered "id=\"b\""))))
  (testing "a single entry renders without a rule"
    (is (not (str/includes? (html (views/entries-view nil {} [{:file   "a"
                                                               :senses []}]))
                            "<hr")))))

(deftest sense-index-test
  (let [entries  [{:file            "a"
                   :headword        "sti"
                   :homographNumber "1"
                   :senses          [{:id "s1" :indicator "sti 1§1"}
                                     {:id "s2"
                                      :definitions [{:text "en smal vej"}]}]}
                  {:file     "b"
                   :headword "sti"
                   :senses   [{:id "s3" :indicator "sti 2§1"}]}]
        nav      {:spy "s2"}
        rendered (html (list (views/index-panel nil nav entries)
                             (views/index-disclosure nil nav entries)))]
    (testing "senses link through the sense fragment of their entry page"
      (is (str/includes? rendered "href=\"entry/a/#s1\""))
      (is (str/includes? rendered "href=\"entry/b/#s3\"")))
    (testing "the label is the indicator, or the first definition"
      (is (str/includes? rendered "sti 1§1"))
      (is (str/includes? rendered "en smal vej")))
    (testing "the entries of a group head their sense lists"
      (is (str/includes? rendered "class=\"index-entry\"")))
    (testing "the home entry of the marked sense is marked with it"
      (is (str/includes? rendered "href=\"entry/a/\" class=\"index-entry current\""))
      (is (str/includes? rendered "href=\"entry/b/\" class=\"index-entry\"")))
    (testing "the group divider recurs between the entries of the index"
      (is (str/includes? rendered "<hr")))
    (testing "both placements render: the panel and the disclosure"
      (is (str/includes? rendered "class=\"sense-index\""))
      (is (str/includes? rendered "class=\"sense-index-inline\""))))
  (testing "a single sense needs no index"
    (is (nil? (views/index-panel nil {} [{:file "a" :senses [{:id "s1"}]}])))
    (is (nil? (views/index-disclosure nil {} [{:file   "a"
                                               :senses [{:id "s1"}]}]))))
  (testing "a lone entry heads its list too, marked, without a divider"
    (let [rendered (html (views/index-panel nil {:spy "s1"}
                                            [{:file     "a"
                                              :headword "abe"
                                              :senses   [{:id "s1"} {:id "s2"}]}]))]
      (is (str/includes? rendered "href=\"entry/a/\" class=\"index-entry current\"")
          "the way back to the entry's top, marked with its sense")
      (is (not (str/includes? rendered "<hr"))))))

(deftest entry-view-test
  (let [rendered (html (views/entry-view
                         nil {}
                         {:headword      "fest"
                          :partsOfSpeech [{:tag "noun" :description "sb."}]
                          :inline-labels [{:tag       "positiv"
                                           :type      "sentiment"
                                           :display   "valør"
                                           :qualifier "1"}]
                          :labels        [{:tag "zoo" :type "domain"}]
                          :senses        []}))]
    (testing "inline labels join the part-of-speech line"
      (is (str/includes? rendered "class=\"inline-label\""))
      (is (str/includes? rendered "valør: ")
          "the display name stays in the markup for assistive tech")
      (is (str/includes? rendered "title=\"valør\"")
          "the tooltip names the attribute by its display name")
      (is (str/includes? rendered " (1)")
          "the combined qualifier trails in parentheses"))
    (testing "the remaining entry labels sit in a titled box"
      (is (str/includes? rendered "class=\"titled\""))
      (is (str/includes? rendered "about the word"))))
  (testing "an entry without labels renders no box"
    (is (not (str/includes? (html (views/entry-view nil {} {:headword "x"
                                                            :senses   []}))
                            "class=\"titled\""))))
  (testing "the sense on screen carries the on-screen class"
    (is (str/includes?
          (html (views/entry-view nil {:spy "s1"} {:file   "x"
                                                   :senses [{:id "s1"}]}))
          "on-screen")))
  (testing "a sense with more than a meaning line folds under it"
    (let [rendered (html (views/entry-view
                           nil {} {:file   "x"
                                   :senses [{:id          "s1"
                                             :definitions [{:text "at sejle"}]
                                             :examples    [{:example "han sejler"}]}]}))]
      (is (str/includes? rendered "<details open class=\"sense-body\">")
          "the fold opens by default")
      (is (str/includes? rendered "<summary class=\"meaning\"")
          "the meaning line is the summary the fold leaves in view")
      (is (str/includes? rendered "class=\"fold-mark\"")
          "the meaning line carries the fold mark")))
  (testing "a sense the reader folded renders closed"
    (let [entry    {:file   "x"
                    :senses [{:id          "s1"
                              :definitions [{:text "at sejle"}]
                              :examples    [{:example "han sejler"}]}]}
          rendered (html (views/entry-view nil {:folded #{"s1"}} entry))]
      (is (str/includes? rendered "<details class=\"sense-body\">")
          "so a re-render restores the fold instead of springing it open")
      (is (not (str/includes? rendered "<details open")))))
  (testing "a sense of nothing but a meaning line stays a paragraph"
    (let [rendered (html (views/entry-view
                           nil {} {:file   "x"
                                   :senses [{:id          "s1"
                                             :definitions [{:text "at sejle"}]}]}))]
      (is (str/includes? rendered "<p class=\"meaning\""))
      (is (not (str/includes? rendered "<details")))))
  (testing "the navigated sense is the current location"
    (is (str/includes?
          (html (views/entry-view nil {:current "s1"} {:file   "x"
                                                       :senses [{:id "s1"}]}))
          "aria-current=\"location\""))))

(deftest search-view-test
  (testing "results render once the index is loaded"
    (is (some? (views/search-view nil (views/matches index "abe") nil "abe" nil))))
  (testing "an index failure surfaces as an error paragraph"
    (is (= :p.error (first (views/search-view nil nil "index.json: 404"
                                              "abe" nil)))))
  (testing "nothing renders while the index is still loading"
    (is (nil? (views/search-view nil nil nil "abe" nil)))))

(deftest front-matter-view-test
  (testing "the merged metadata fields render on the front page"
    (let [rendered (html (views/front-matter-view
                           {:description "Det danske WordNet."
                            :rights      "© DSL & CST"
                            :license     "https://example.com/by-sa"
                            :sources     [{:title       "DDS"
                                           :full        "Det Danske Sentimentleksikon"
                                           :uri         "https://example.com/dds"
                                           :license     "https://example.com/cc0"
                                           :licenseName "CC0 1.0"}
                                          {:title "NN"}]}))]
      (is (str/includes? rendered "Det danske WordNet."))
      (is (str/includes? rendered "© DSL &amp; CST"))
      (is (str/includes? rendered "https://example.com/by-sa"))
      (is (str/includes? rendered "https://example.com/cc0"))
      (is (str/includes? rendered "title=\"Det Danske Sentimentleksikon\"")
          "an abbreviated source shows its full name on hover")
      (is (str/includes? rendered "https://example.com/dds")
          "a source with a home URI links its name")
      (is (str/includes? rendered "CC0 1.0")
          "a named license shows its short name over the URL")
      (is (str/includes? rendered "sources &amp; licences")
          "the group title mentions licences when a source carries one")
      (is (str/includes? rendered "aria-labelledby")
          "the sections are named landmarks for assistive technology"))
    (let [rendered (html (views/front-matter-view {:sources [{:title "NN"}]}))]
      (is (str/includes? rendered ">sources<"))
      (is (not (str/includes? rendered "licences"))
          "licence-less sources title the group plain")
      (is (str/includes? rendered ">NN<")
          "a bare title renders without a link or license")))
  (testing "a manifest without metadata renders nothing"
    (is (nil? (views/front-matter-view {:title "Test" :entries 5})))))

(deftest app-test
  (testing "only the empty sheet renders until the manifest arrives"
    (is (= [:div.container] (views/app {:query ""}))))
  (testing "the full page renders once the manifest is in"
    (is (str/includes? (html (views/app {:manifest {:title "T"} :query ""}))
                       "<search>")))
  (testing "the pre-rendered page carries no event handler or hook"
    (let [rendered (html (views/app {:manifest {:title "T"}
                                     :query    ""
                                     :entries  [{:file   "a"
                                                 :senses [{:id "s1"}]}]
                                     :nav      {:file   "a"
                                                :reveal {:file   "a"
                                                         :scroll :top}}}))]
      (is (not (str/includes? rendered "replicant")))
      (is (not (str/includes? rendered ":app/reveal"))))))

(deftest desk-aside-test
  (let [entries [{:file     "a"
                  :headword "sti"
                  :senses   [{:id "s1"} {:id "s2"}]}]
        prefs   [:span.toggles "prefs"]
        colo    (views/footer-view {:title "T" :entries 1})
        with    (html (views/desk-aside nil {} entries prefs colo))
        without (html (views/desk-aside nil {} [] prefs colo))]
    (testing "the column carries the index, the preferences and the colophon"
      (is (str/includes? with "class=\"sense-index\""))
      (is (str/includes? with "class=\"prefs\""))
      (is (str/includes? with "class=\"colophon\""))
      (is (< (str/index-of with "sense-index")
             (str/index-of with "class=\"prefs\"")
             (str/index-of with "class=\"colophon\""))))
    (testing "a page without an index keeps its preferences"
      (is (not (str/includes? without "class=\"sense-index\"")))
      (is (str/includes? without "class=\"prefs\"")))
    (testing "the aside is named for assistive technology"
      (is (str/includes? without "aria-label=\"preferences\"")))))

(deftest language-select-test
  (let [languages ["da" "en"]]
    (testing "the offered languages render as options"
      (let [rendered (html (views/language-select nil nil {:langCode "da"}
                                                  languages))]
        (is (str/includes? rendered "value=\"da\""))
        (is (str/includes? rendered "value=\"en\""))))
    (testing "the resource language is the default when offered"
      (is (str/includes? (html (views/language-select nil nil {:langCode "da"}
                                                      languages))
                         "selected")))
    (testing "an unoffered resource language falls back to English"
      (is (not (str/includes? (html (views/language-select nil nil
                                                           {:langCode "sv"}
                                                           languages))
                              "value=\"sv\""))))))

(deftest results-view-test
  (let [rendered (html (views/results-view nil (views/matches index "ab")
                                           "ab" 1))]
    (testing "the rows form a listbox of options for the combobox"
      (is (str/includes? rendered "role=\"listbox\""))
      (is (str/includes? rendered "id=\"result-0\"")))
    (testing "the active row is the selected option"
      (is (str/includes? rendered "aria-selected=\"true\"")))
    (testing "a result links to the entry page"
      (is (str/includes? rendered "href=\"entry/abe/\"")))))
