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
      (is (str/includes? rendered ":abbr")
          "an abbreviated source expands its full name on hover")
      (is (str/includes? rendered "Det Danske Sentimentleksikon"))
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
