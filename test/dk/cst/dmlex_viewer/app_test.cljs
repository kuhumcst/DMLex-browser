(ns dk.cst.dmlex-viewer.app-test
  "Tests of the pure search and view logic of the frontend."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-viewer.app :as app]
            [dk.cst.dmlex-viewer.shared :as shared]))

(def index
  [{:headword "Abe" :lower "abe" :file "abe"}
   {:headword "abekat" :lower "abekat" :file "abekat"}
   {:headword "bavian" :lower "bavian" :file "bavian"}])

(deftest matches-test
  (testing "a case-insensitive prefix filter"
    (is (= ["Abe" "abekat"] (map :headword (app/matches index "AB")))))
  (testing "the optional limit"
    (is (= ["Abe"] (map :headword (app/matches index "ab" 1)))))
  (testing "no matches"
    (is (= [] (app/matches index "xyz")))))

(deftest distinct-by-test
  (is (= [{:x 1 :y :a} {:x 2 :y :c}]
         (shared/distinct-by :x [{:x 1 :y :a} {:x 1 :y :b} {:x 2 :y :c}]))))

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
                                  {:text "årene" :short "-ene"}])))))))

(deftest search-view-test
  (testing "results render once the index is loaded"
    (is (some? (app/search-view index nil "abe"))))
  (testing "an index failure surfaces as an error paragraph"
    (is (= :p.error (first (app/search-view nil "index.json: 404" "abe")))))
  (testing "nothing renders while the index is still loading"
    (is (nil? (app/search-view nil nil "abe")))))
