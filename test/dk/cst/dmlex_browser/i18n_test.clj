(ns dk.cst.dmlex-browser.i18n-test
  "Tests of the gettext extraction and the drift guard of the template."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-browser.i18n :as i18n]
            [dk.cst.dmlex-browser.translations :as translations]
            [pottery.core :as pottery]))

(deftest extract-test
  (testing "direct keys, joined strings and conditional branches extract"
    (is (= ["a"] (i18n/extract '(tr "a"))))
    (is (= ["a"] (i18n/extract '(en "a"))))
    (is (= ["a"] (i18n/extract '(shared/tr ui "a" n))))
    (is (= ["a b"] (i18n/extract '(tr (str "a " "b")))))
    (is (= ["a" "b"]
           (i18n/extract '(let [s (if x "a" "b")] [:p {:lang (en s)} (tr s)])))))
  (testing "strings unrelated to tr and en do not extract"
    (is (nil? (i18n/extract '(case x "a" 1 "b" 2))))
    (is (empty? (i18n/extract '(let [s (if x "a" "b")] [:p s]))))))

(deftest template-drift-test
  (is (= (set (i18n/extracted-keys i18n/source-files))
         (set (keys (pottery/read-po-file (io/file i18n/template-file)))))
      "the template is stale; regenerate it with: clojure -M:i18n"))

(deftest bundled-translations-test
  (doseq [[lang table] (translations/tables)]
    (is (= (set (i18n/extracted-keys i18n/source-files))
           (set (keys table)))
        (str "the bundled " lang " translation is incomplete or stale"))))
