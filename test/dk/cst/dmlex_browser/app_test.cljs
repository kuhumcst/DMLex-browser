(ns dk.cst.dmlex-browser.app-test
  "Tests of the pure routing and keyboard logic of the frontend."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-browser.app :as app]))

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

(deftest ->route-test
  (testing "an entry page names its entry"
    (is (= {:file "abe" :sense nil} (app/->route "entry/abe/")))
    (is (= {:file "abe" :sense nil} (app/->route "entry/abe"))))
  (testing "the fragment names a sense of the entry"
    (is (= {:file "abe" :sense "abe-1"} (app/->route "entry/abe/#abe-1"))))
  (testing "the site root is the front page"
    (is (= {} (app/->route "")))
    (is (= {} (app/->route "index.html"))))
  (testing "any other path is no page of the app, so the browser keeps it"
    (is (nil? (app/->route "entry/abe/extra")))
    (is (nil? (app/->route "data/index.json")))))

(deftest ->reveal-test
  (let [entries [{:file "a" :senses [{:id "s1"} {:id "s2"}]}
                 {:file "b" :senses [{:id "s3"}]}]
        entry   (first entries)]
    (testing "arriving from another page shows the group from the top"
      (is (= {:file "a" :sense nil :scroll :top}
             (app/->reveal entries entry nil false false))))
    (testing "a later entry of the group scrolls into view instead"
      (is (= {:file "b" :sense nil :scroll :entry}
             (app/->reveal entries (second entries) nil false false))))
    (testing "a named sense takes the focus and scrolls to itself"
      (is (= {:file "a" :sense "s2" :scroll :sense}
             (app/->reveal entries entry "s2" false false))))
    (testing "the first sense of an entry arrived at reveals the entry"
      (is (= {:file "a" :sense "s1" :scroll :top}
             (app/->reveal entries entry "s1" false false))))
    (testing "within the same entry even its first sense scrolls itself"
      (is (= {:file "a" :sense "s1" :scroll :sense}
             (app/->reveal entries entry "s1" true true))))
    (testing "a sense the entry does not carry reveals the entry"
      (is (= {:file "a" :sense nil :scroll :entry}
             (app/->reveal entries entry "s3" true true))))))

(deftest next-active-test
  (testing "Down enters the list at the top and stops at the bottom"
    (is (= 0 (app/next-active "ArrowDown" nil 3)))
    (is (= 2 (app/next-active "ArrowDown" 1 3)))
    (is (= 2 (app/next-active "ArrowDown" 2 3))))
  (testing "Up leaves the list at the top and enters it at the bottom"
    (is (nil? (app/next-active "ArrowUp" 0 3)))
    (is (= 2 (app/next-active "ArrowUp" nil 3)))
    (is (= 0 (app/next-active "ArrowUp" 1 3)))))

(deftest reading-line-test
  (testing "the line rests a quarter down the viewport"
    (is (= 250 (app/reading-line 1000 4000 4000))))
  (testing "over the closing screenful it slides down to the foot"
    (is (= 250 (app/reading-line 1000 4000 750)))
    (is (= 500 (app/reading-line 1000 4000 500)))
    (is (= 1000 (app/reading-line 1000 4000 0))))
  (testing "a page too short to scroll keeps the line where it started"
    (is (= 250 (app/reading-line 1000 0 0))))
  (testing "the line slides no further than the page has scrolled"
    (is (= 350 (app/reading-line 1000 100 0)))))

(deftest current-sense-test
  (testing "the last sense past the reading line carries the mark"
    (is (= "s2" (app/current-sense [["s1" -500] ["s2" 100] ["s3" 900]] 250)))
    (is (= "s2" (app/current-sense [["s2" -1200] ["s3" 900]] 250))
        "including a sense too tall to show its meaning"))
  (testing "the closing senses take the mark one by one as the line slides"
    (let [up   (fn [px] (mapv (fn [[id top]] [id (+ top px)])
                              [["s11" -700] ["s12" -300] ["s13" 200]]))
          mark (fn [px] (app/current-sense (up px)
                                           (app/reading-line 1000 (- 4000 px)
                                                             px)))]
      (is (= "s13" (mark 0))
          "the last sense holds the mark at the foot of the page")
      (is (= "s12" (mark 500)))
      (is (= "s11" (mark 700)))))
  (testing "at the page top the first sense carries the mark"
    (is (= "s1" (app/current-sense [["s1" 400] ["s2" 900]] 250))))
  (testing "without senses there is no mark"
    (is (nil? (app/current-sense [] 250)))
    (is (nil? (app/current-sense [] 1000)))))
