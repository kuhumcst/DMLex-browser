(ns dk.cst.dmlex-browser.appledict-test
  "Tests of the XML emission and entry rendering of the Apple Dictionary
  export."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-browser.appledict :as appledict]
            [dk.cst.dmlex-browser.build :as build]
            [dk.cst.dmlex-browser.build-test :as build-test]))

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
    (is (nil? (appledict/inflections-view nil "år" [{:text "år"}])))
    (is (str/includes?
          (appledict/hiccup->xml
            (appledict/inflections-view nil "år" [{:text "år"}
                                              {:text "årene" :short "-ene"}]))
          ">-ene</dd>"))))

(deftest paradigm-view-test
  (testing "variant forms share their paradigm row"
    (is (str/includes?
          (appledict/hiccup->xml
            (appledict/paradigm-view
              nil
              [{:tag "110" :description "sg" :text "68'er"}
               {:tag "110" :description "sg" :text "otteogtresser"}
               {:tag "111" :description "sg def" :text "68'eren"}]))
          (str "<th scope=\"row\" data-label=\"sg\"></th>"
               "<td>68'er, otteogtresser</td></tr>"
               "<tr><th scope=\"row\" data-label=\"sg def\"></th>"
               "<td>68'eren</td>")))))

(deftest ->entry-test
  (let [env   (build/->env build-test/resource)
        xml   (appledict/hiccup->xml
                (appledict/->entry
                  nil
                  (build/->entry-file env (first (:entries build-test/resource)))))]
    (testing "the entry id and title"
      (is (str/includes? xml "<d:entry id=\"hund\" class=\"entry\" d:title=\"hund\">")))
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
      (is (str/includes? xml "title=\"subject domain\"")))
    (testing "entry labels sit in a titled box under the generic heading"
      (is (str/includes? xml "<div class=\"labels-section titled\""))
      (is (str/includes? xml "data-label=\"about the word\"")))
    (testing "the pos tag and the example source link by their sameAs URI"
      (is (str/includes? xml "href=\"https://example.com/sb\""))
      (is (str/includes? xml "href=\"https://ordnet.dk/ddo\"")))
    (testing "the pos shows its description with the tag as the tooltip"
      (is (str/includes? xml "<span title=\"sb.\">substantiv</span>")))
    (testing "the marked headword of an example renders in bold"
      (is (str/includes? xml "en stor <b>hund</b>")))
    (testing "the labels of an example trail it in parentheses"
      (is (str/includes? xml "<span class=\"example-labels\"> (")))
    (testing "headword translations group by language"
      (is (str/includes?
            xml
            (str "<dl class=\"labels translations\" d:priority=\"2\">"
                 "<div><dt>en</dt><dd lang=\"en\">dog, hound</dd></div></dl>"))))
    (testing "the definition carries its type silently"
      (is (str/includes?
            xml
            (str "<span class=\"definition\" data-type=\"short\""
                 " title=\"short definition\">et dyr</span>"))))))

(deftest presentation-rendering-test
  (testing "renamed label types and relation roles reach the XML"
    (is (str/includes?
          (appledict/hiccup->xml
            (appledict/labels-view nil "entry-labels"
                                   [{:tag "zoo" :type "domain"
                                     :display "emne"}]))
          "<dt>emne</dt>"))
    (is (str/includes?
          (appledict/hiccup->xml
            (appledict/relations-dl
              nil
              [{:type "hyp" :role "hypernym" :display-role "overbegreb"
                :members [{:headword "H" :file "h"}]}]))
          "<dt title=\"hyp\">overbegreb</dt>"))
    (is (str/includes?
          (appledict/hiccup->xml
            (appledict/label-dd {:tag "Neutral" :type "sentiment"
                                 :qualifier "0"}))
          "Neutral (0)"))
    (is (= (str "<span class=\"inline-label\">"
                "<span class=\"visually-hidden\">valør: </span>"
                "<span title=\"valør: Positive\">positiv</span>"
                " (1)</span>")
           (appledict/hiccup->xml
             (appledict/inline-label-view {:tag         "positiv"
                                           :type        "sentiment"
                                           :display     "valør"
                                           :description "Positive"
                                           :qualifier   "1"}))))))

(deftest sense-index-test
  (testing "senses link by x-dictionary ref, labelled by indicator"
    (let [items (str "<ol class=\"index-senses\">"
                     "<li><a href=\"x-dictionary:r:a:#s1\">sti 1§1</a></li>"
                     "<li>en smal vej</li>"
                     "</ol>")]
      (is (= (str "<div class=\"sense-index-anchor\" d:priority=\"2\">"
                  "<nav class=\"sense-index\">" items "</nav></div>"
                  "<details class=\"sense-index-inline\" d:priority=\"2\">"
                  "<summary class=\"contents\"></summary>"
                  "<nav>" items "</nav></details>")
             (appledict/hiccup->xml
               (appledict/sense-index
                 "a" [{:id "s1" :indicator "sti 1§1"}
                      {:definitions [{:text "en smal vej"}]}]))))))
  (testing "a single sense needs no index"
    (is (nil? (appledict/sense-index "a" [{:id "s1"}])))))

(deftest chrome-css-test
  (testing "nothing without translated chrome strings"
    (is (nil? (appledict/chrome-css nil))))
  (testing "translated strings override the content rules of the base CSS"
    (is (= (str "summary.all-forms::after { content: \"alle former\"; }\n"
                "summary.contents::after { content: \"indhold\"; }\n"
                ".relations summary::after"
                " { content: attr(data-count) \" ord\"; }")
           (appledict/chrome-css {"all forms"   "alle former"
                                  "contents"    "indhold"
                                  "{n} entries" "{n} ord"})))))

(deftest front-matter-translation-test
  (testing "the About title translates through the ui table"
    (is (str/includes?
          (appledict/front-matter-xml {"About {title}" "Om {title}"}
                                      {:title "DanNet"} nil)
          "d:title=\"Om DanNet\""))
    (is (str/includes?
          (appledict/front-matter-xml nil {:title "DanNet"} nil)
          "d:title=\"About DanNet\""))))

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
            :licenseName "CC BY-SA 4.0"
            :sources     []}
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
