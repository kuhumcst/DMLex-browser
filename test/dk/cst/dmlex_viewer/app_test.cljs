(ns dk.cst.dmlex-viewer.app-test
  "Tests of the pure routing and keyboard logic of the frontend."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-viewer.app :as app]))

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
  (testing "any other path is no page of the viewer, so the browser keeps it"
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
