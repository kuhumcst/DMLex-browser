(ns dk.cst.dmlex-viewer.appledict-test
  "Tests of the XML emission and entry rendering of the Apple Dictionary
  export."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-viewer.appledict :as appledict]
            [dk.cst.dmlex-viewer.build :as build]
            [dk.cst.dmlex-viewer.build-test :as build-test]))

(deftest hiccup->xml-test
  (testing "elements, attributes and the d: prefix convention"
    (is (= "<p class=\"pos\">sb.</p>"
           (appledict/hiccup->xml [:p {:class "pos"} "sb."])))
    (is (= "<d:index d:value=\"hund\"/>"
           (appledict/hiccup->xml [:d/index {:d/value "hund"}]))
        "a childless element self-closes"))
  (testing "escaping in text and attributes"
    (is (= "<i title=\"a &amp; b\">&lt;x&gt;</i>"
           (appledict/hiccup->xml [:i {:title "a & b"} "<x>"]))))
  (testing "nil children and attributes render as nothing"
    (is (= "<div>ab</div>"
           (appledict/hiccup->xml [:div nil (list "a" nil "b")])))
    (is (= "<a>x</a>"
           (appledict/hiccup->xml [:a {:title nil} "x"]))))
  (testing "non-string content is stringified"
    (is (= "<sup>2</sup>" (appledict/hiccup->xml [:sup 2])))))

(deftest ->index-test
  (is (= [[:d/index {:d/value "hund"}]
          [:d/index {:d/value "hunde" :d/title "hunde (hund)"}]]
         (appledict/->index "hund" [{:text "hunde"}
                                    {:text "hunde"}
                                    {:text "hund"}]))
      "forms are distinct and the headword itself is not repeated"))

(deftest inflections-view-test
  (testing "a form spelled like the headword stays out of the line"
    (is (nil? (appledict/inflections-view "år" [{:text "år"}])))
    (is (str/includes?
          (appledict/hiccup->xml
            (appledict/inflections-view "år" [{:text "år"}
                                              {:text "årene" :short "-ene"}]))
          ">-ene</dd>"))))

(deftest ->entry-test
  (let [env   (build/->env build-test/resource)
        xml   (appledict/hiccup->xml
                (appledict/->entry
                  (build/->entry-file env (first (:entries build-test/resource)))))]
    (testing "the entry id and title"
      (is (str/includes? xml "<d:entry id=\"hund\" d:title=\"hund\">")))
    (testing "full inflected forms become index terms"
      (is (str/includes? xml "<d:index d:value=\"hunde\" d:title=\"hunde (hund)\"/>")))
    (testing "the inflections line shows the affixed short form"
      (is (str/includes? xml ">-e</dd>")))
    (testing "the full paradigm sits behind a details disclosure"
      (is (str/includes? xml "<details class=\"paradigm\" d:priority=\"2\">"))
      (is (str/includes? xml "<td>hunde</td>")))
    (testing "relation members link by x-dictionary ref"
      (is (str/includes? xml (str "x-dictionary:r:" (build/->file "køter")))))
    (testing "labels resolve with description and URI"
      (is (str/includes? xml "href=\"https://example.com/zoo\""))
      (is (str/includes? xml "title=\"subject domain\"")))))

(deftest bundle-info-test
  (testing "metadata wins over the resource fields, with derived identifier"
    (is (= {:title       "DanNet"
            :identifier  "dmlex.dannet.dictionary"
            :version     "2026-08-14"
            :lang        "da"
            :uri         "https://wordnet.dk/dannet/"
            :description "Det danske WordNet."
            :publisher   "CST"
            :rights      "© DSL & CST"
            :license     "https://creativecommons.org/licenses/by-sa/4.0/"
            :sources     nil}
           (appledict/bundle-info
             {:title "ignored" :langCode "da"}
             {"dc:title"       "DanNet"
              "dc:identifier"  "https://wordnet.dk/dannet/"
              "dc:issued"      "2026-08-14"
              "dc:description" {"en" "The Danish WordNet."
                                "da" "Det danske WordNet."}
              "dc:publisher"   "CST"
              "dc:rights"      "© DSL & CST"
              "dc:license"     "https://creativecommons.org/licenses/by-sa/4.0/"}))))
  (testing "without metadata, the resource fields carry the bundle"
    (is (= {:title      "Test"
            :identifier "dmlex.test.dictionary"
            :version    "1.0"
            :lang       "da"}
           (-> (appledict/bundle-info {:title "Test" :langCode "da"} nil)
               (select-keys [:title :identifier :version :lang]))))))

(deftest info-plist-test
  (let [plist (appledict/info-plist {:title  "A & B" :identifier "x"
                                     :version "1" :lang "da"
                                     :rights "© Somebody"})]
    (testing "values are XML-escaped"
      (is (str/includes? plist "<string>A &amp; B</string>")))
    (testing "optional keys only appear when present"
      (is (str/includes? plist "DCSDictionaryCopyright"))
      (is (not (str/includes? plist "DCSDictionaryManufacturerName"))))))
