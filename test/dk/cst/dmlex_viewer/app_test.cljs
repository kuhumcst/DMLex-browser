(ns dk.cst.dmlex-viewer.app-test
  "Tests of the pure search and view logic of the frontend."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-viewer.app :as app]
            [dk.cst.dmlex-viewer.shared :as shared]))

(def index
  [{:headword "Abe" :lower "abe" :file "abe"}
   {:headword "abekat" :lower "abekat" :file "abekat"}
   {:headword "bavian" :lower "bavian" :file "bavian"}])

(deftest matches-test
  (testing "a case-insensitive prefix filter"
    (is (= ["Abe" "abekat"] (map :headword (app/matches index "AB")))))
  (testing "no matches"
    (is (= [] (app/matches index "xyz")))))

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
  (swap! app/state assoc :presentation
         {"ui" {"entries"   "opslagsord"
                "senses"    "betydninger"
                "relations" "relationer"}})
  (try
    (let [rendered (pr-str (app/footer-view {:title   "T"
                                             :entries 1 :senses 2 :relations 3}))]
      (testing "translated chrome renders in the dataset's language"
        (is (str/includes? rendered "opslagsord")))
      (testing "a translated string drops its English lang marker"
        (is (not (str/includes? rendered "\"en\"")))))
    (finally
      (swap! app/state dissoc :presentation))))

(deftest runs-view-test
  (testing "marker runs render the marked headword in bold"
    (is (= (list "en stor " [:b "hund"])
           (app/runs-view "en stor hund"
                          [{:text "en stor "}
                           {:text "hund" :marker "headword"}]))))
  (testing "text without runs renders plain"
    (is (= "en stor hund" (app/runs-view "en stor hund" nil)))))

(deftest result-headword-test
  (testing "the matched prefix is marked"
    (is (= (list [:mark "ab"] "ekat")
           (app/result-headword "abekat" "ab"))))
  (testing "a query longer than the headword marks nothing"
    (is (= "abe" (app/result-headword "abe" "abekat")))))

(deftest inflections-view-test
  (testing "a form spelled like the headword stays out of the line"
    (is (nil? (app/inflections-view "år" [{:text "år"}])))
    (is (= 1 (count (rest (app/inflections-view
                            "år" [{:text "år"}
                                  {:text "årene" :short "-ene"}]))))))
  (testing "the reduced form represents its slot; the variant stays out"
    (is (= 1 (count (rest (app/inflections-view
                            "hund" [{:tag "111" :text "hunden" :short "-en"}
                                    {:tag "111" :text "hund-en"}])))))))

(deftest collapse-homographs-test
  (testing "rows sharing headword and pos collapse to their first row"
    (is (= [{:headword "sti" :pos "sb." :file "sti-1"}
            {:headword "sti" :pos "vb." :file "sti-3"}
            {:headword "vej" :pos "sb." :file "vej"}]
           (app/collapse-homographs
             [{:headword "sti" :pos "sb." :file "sti-1"}
              {:headword "sti" :pos "sb." :file "sti-2"}
              {:headword "sti" :pos "vb." :file "sti-3"}
              {:headword "vej" :pos "sb." :file "vej"}])))))

(deftest entries-view-test
  (testing "a homograph group renders as articles divided by rules"
    (let [rendered (pr-str (app/entries-view [{:file "a" :senses []}
                                              {:file "b" :senses []}]))]
      (is (= 1 (count (re-seq #":hr.homograph" rendered))))
      (is (str/includes? rendered ":id \"a\""))
      (is (str/includes? rendered ":id \"b\""))))
  (testing "a single entry renders without a rule"
    (is (not (str/includes? (pr-str (app/entries-view [{:file "a" :senses []}]))
                            ":hr.homograph")))))

(deftest current-sense-test
  (testing "scrolling down, the last sense past the reading line wins"
    (is (= "s2" (app/current-sense [["s1" -500] ["s2" 100] ["s3" 900]]
                                   1000 "s1" false false))))
  (testing "scrolling up, the meaning nearest the viewport top wins"
    (is (= "s3" (app/current-sense [["s2" -300] ["s3" 20] ["s4" 700]]
                                   1000 "s4" false true))
        "a sense takes the mark back when its meaning returns to view")
    (is (= "s2" (app/current-sense [["s2" -1200] ["s3" 900]]
                                   1000 "s3" false true))
        "inside a sense too tall to show its meaning, it holds the mark")
    (is (= "s2" (app/current-sense [["s2" -200] ["s3" 600]]
                                   1000 "s2" false true))
        "the mark never moves down the page on the way up"))
  (testing "a stale spy far down the page yields to the reading line"
    (is (= "s2" (app/current-sense [["s2" -100] ["s3" 400] ["s4" 800]]
                                   1000 "s4" false false))
        "e.g. after a viewport resize, without an up-scroll"))
  (testing "at the end of the page the last sense takes the mark"
    (is (= "s4" (app/current-sense [["s3" -100] ["s4" 500]] 1000 "s3" true
                                   false))
        "the reading line cannot reach a short final sense"))
  (testing "at the page top the first sense in reach carries the mark"
    (is (= "s1" (app/current-sense [["s1" 400] ["s2" 900]] 1000 nil false
                                   false))))
  (testing "without senses there is no mark"
    (is (nil? (app/current-sense [] 1000 nil false false)))
    (is (nil? (app/current-sense [] 1000 nil true true)))))

(deftest present-entries-test
  (testing "the presentation of a group is cached between renders"
    (let [entries [{:headword "x" :senses [{:id "s1"}]}]
          a       (app/present-entries nil nil "da" entries)
          b       (app/present-entries nil nil "da" entries)]
      (is (identical? a b)))))

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
        rendered (pr-str (list (app/index-panel "s2" entries)
                               (app/index-disclosure "s2" entries)))]
    (testing "senses link through the existing sense routes"
      (is (str/includes? rendered "#/entry/a/s1"))
      (is (str/includes? rendered "#/entry/b/s3")))
    (testing "the label is the indicator, or the first definition"
      (is (str/includes? rendered "sti 1§1"))
      (is (str/includes? rendered "en smal vej")))
    (testing "the spied sense is marked as current"
      (is (str/includes? rendered ":class \"current\"")))
    (testing "the entries of a group head their sense lists"
      (is (str/includes? rendered ":a.index-entry")))
    (testing "the home entry of the marked sense is marked with it"
      (is (str/includes? rendered ":href \"#/entry/a\", :class \"current\""))
      (is (not (str/includes? rendered ":href \"#/entry/b\", :class"))))
    (testing "the group divider recurs between the entries of the index"
      (is (str/includes? rendered ":hr.homograph")))
    (testing "both placements render: the panel and the disclosure"
      (is (str/includes? rendered ":nav.sense-index"))
      (is (str/includes? rendered ":details.sense-index-inline"))))
  (testing "a single sense needs no index"
    (is (nil? (app/index-panel nil [{:file   "a"
                                     :senses [{:id "s1"}]}])))
    (is (nil? (app/index-disclosure nil [{:file   "a"
                                          :senses [{:id "s1"}]}]))))
  (testing "a lone entry heads its list too, marked, without a divider"
    (let [rendered (pr-str (app/index-panel "s1" [{:file     "a"
                                                   :headword "abe"
                                                   :senses   [{:id "s1"}
                                                              {:id "s2"}]}]))]
      (is (str/includes? rendered
                         ":a.index-entry {:href \"#/entry/a\", :class \"current\"}")
          "the way back to the entry's top, marked with its sense")
      (is (not (str/includes? rendered ":hr.homograph"))))))

(deftest entry-view-test
  (let [rendered (pr-str (app/entry-view
                           {:headword      "fest"
                            :partsOfSpeech [{:tag "noun" :description "sb."}]
                            :inline-labels [{:tag       "positiv"
                                             :type      "sentiment"
                                             :display   "valør"
                                             :qualifier "1"}]
                            :labels        [{:tag "zoo" :type "domain"}]
                            :senses        []}))]
    (testing "inline labels join the part-of-speech line"
      (is (str/includes? rendered ":span.inline-label"))
      (is (str/includes? rendered "\"valør: \"")
          "the display name stays in the markup for assistive tech")
      (is (str/includes? rendered ":title \"valør\"")
          "the tooltip names the attribute by its display name")
      (is (str/includes? rendered "\" (1)\"")
          "the combined qualifier trails in parentheses"))
    (testing "the remaining entry labels sit in a titled box"
      (is (str/includes? rendered ":section.titled"))
      (is (str/includes? rendered "\"about the word\""))))
  (testing "an entry without labels renders no box"
    (is (not (str/includes? (pr-str (app/entry-view {:headword "x" :senses []}))
                            ":section.titled"))))
  (testing "the sense on screen carries the on-screen class"
    (is (str/includes?
          (pr-str (app/entry-view {:file   "x"
                                   :senses [{:id "s1" :spy? true}]}))
          "on-screen"))))

(deftest search-view-test
  (testing "results render once the index is loaded"
    (is (some? (app/search-view (app/matches index "abe") nil "abe" nil))))
  (testing "an index failure surfaces as an error paragraph"
    (is (= :p.error (first (app/search-view nil "index.json: 404" "abe" nil)))))
  (testing "nothing renders while the index is still loading"
    (is (nil? (app/search-view nil nil "abe" nil)))))

(deftest front-matter-view-test
  (testing "the merged metadata fields render on the front page"
    (let [rendered (pr-str (app/front-matter-view
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
      (is (str/includes? rendered "© DSL & CST"))
      (is (str/includes? rendered "https://example.com/by-sa"))
      (is (str/includes? rendered "https://example.com/cc0"))
      (is (str/includes? rendered ":title \"Det Danske Sentimentleksikon\"")
          "an abbreviated source shows its full name on hover")
      (is (str/includes? rendered "https://example.com/dds")
          "a source with a home URI links its name")
      (is (str/includes? rendered "CC0 1.0")
          "a named license shows its short name over the URL")
      (is (str/includes? rendered "sources & licences")
          "the group title mentions licences when a source carries one")
      (is (str/includes? rendered ":aria-labelledby")
          "the sections are named landmarks for assistive technology"))
    (let [rendered (pr-str (app/front-matter-view
                             {:sources [{:title "NN"}]}))]
      (is (str/includes? rendered "\"sources\""))
      (is (not (str/includes? rendered "licences"))
          "licence-less sources title the group plain")
      (is (str/includes? rendered "\"NN\"")
          "a bare title renders without a link or license")))
  (testing "a manifest without metadata renders nothing"
    (is (nil? (app/front-matter-view {:title "Test" :entries 5})))))

(deftest app-loading-test
  (testing "only the empty sheet renders until the manifest arrives"
    (is (= [:div.container] (app/app {:query ""}))))
  (testing "the full page renders once the manifest is in"
    (is (str/includes? (pr-str (app/app {:manifest {:title "T"} :query ""}))
                       ":search"))))

(deftest language-select-test
  (testing "the bundled languages and English are offered"
    (let [rendered (pr-str (app/language-select nil {:langCode "da"}))]
      (is (str/includes? rendered ":value \"da\""))
      (is (str/includes? rendered ":value \"en\""))))
  (testing "the resource language is the default when bundled"
    (is (str/includes? (pr-str (app/language-select nil {:langCode "da"}))
                       ":selected true")))
  (testing "an unbundled resource language falls back to English"
    (let [rendered (pr-str (app/language-select nil {:langCode "sv"}))]
      (is (not (str/includes? rendered ":value \"sv\""))))))

(deftest next-active-test
  (testing "Down enters the list at the top and stops at the bottom"
    (is (= 0 (app/next-active "ArrowDown" nil 3)))
    (is (= 2 (app/next-active "ArrowDown" 1 3)))
    (is (= 2 (app/next-active "ArrowDown" 2 3))))
  (testing "Up leaves the list at the top and enters it at the bottom"
    (is (nil? (app/next-active "ArrowUp" 0 3)))
    (is (= 2 (app/next-active "ArrowUp" nil 3)))
    (is (= 0 (app/next-active "ArrowUp" 1 3)))))

(deftest results-view-test
  (let [rendered (pr-str (app/results-view (app/matches index "ab") "ab" 1))]
    (testing "the rows form a listbox of options for the combobox"
      (is (str/includes? rendered ":role \"listbox\""))
      (is (str/includes? rendered ":id \"result-0\"")))
    (testing "the active row is the selected option"
      (is (str/includes? rendered ":aria-selected \"true\"")))))
