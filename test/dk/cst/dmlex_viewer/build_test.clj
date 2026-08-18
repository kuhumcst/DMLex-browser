(ns dk.cst.dmlex-viewer.build-test
  "Tests of the pure resolution logic of the data build."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-viewer.build :as build]
            [dk.cst.dmlex-viewer.presentation :as presentation])
  (:import [java.io File]
           [java.util.zip ZipEntry ZipOutputStream]))

(deftest ->file-test
  (testing "a filename-safe id passes through unchanged"
    (is (= "hund" (build/->file "hund")))
    (is (= "entry_1.2-a" (build/->file "entry_1.2-a"))))
  (testing "an unsafe id keeps its safe characters and gains a hash"
    (is (re-matches #"dn_synset-123-[0-9a-f]+" (build/->file "dn:synset-123"))))
  (testing "distinct unsafe ids with the same safe characters stay distinct"
    (is (not= (build/->file "a:b") (build/->file "a?b")))))

(deftest compact-test
  (is (= {:a 1 :d "x"}
         (build/compact {:a 1 :b nil :c [] :d "x" :e {}})))
  (testing "an empty string is a value, not an empty collection"
    (is (= {:s ""} (build/compact {:s ""})))))

(deftest affix-test
  (testing "a suffix after the longest common prefix"
    (is (= "-t" (build/affix "menneske" "mennesket")))
    (is (= "-e" (build/affix "hund" "hunde"))))
  (testing "prefix notation when the form shares its ending instead"
    (is (= "op-" (build/affix "give" "opgive"))))
  (testing "a multiword compound that extends its last word reduces"
    (is (= "-t" (build/affix "belle de boskoop-æble"
                             "belle de boskoop-æblet")))
    (is (= "-rne" (build/affix "belle de boskoop-æble"
                               "belle de boskoop-æblerne"))))
  (testing "nil when the reduction would mislead"
    (is (nil? (build/affix "slå op" "slog op"))
        "an internally inflecting multiword expression")
    (is (nil? (build/affix "kaste op" "kastede op"))
        "a multiword expression whose remainder crosses the space")
    (is (nil? (build/affix "barn" "børn")) "stem change")
    (is (nil? (build/affix "gå" "gå i stå")) "remainder with a space")
    (is (nil? (build/affix "hund" "hund2")) "remainder without letters")
    (is (nil? (build/affix "år" "år")) "form identical to the headword")
    (is (nil? (build/affix "år" "års-")) "compound stem")))

(deftest member-refs-test
  (is (= {"a" [0 1]
          "b" [0]}
         (build/member-refs [{:members [{:ref "a"} {:ref "b"}]}
                             {:members [{:ref "a"}]}]))))

(def homes
  {"a" {:headword "A" :file "a"}
   "b" {:headword "B" :file "b"}
   "h" {:headword "H" :file "h"}})

(defn env
  "The lookup environment of `relations`, resolving refs against `homes*`."
  ([relations homes*]
   (env relations homes* {}))
  ([relations homes* reltype-of]
   {:relations   relations
    :reltype-of  reltype-of
    :resolve-ref homes*
    :ref->idxs   (build/member-refs relations)}))

(defn rows
  "The relation rows of `ref` in `relations`, resolved against `homes`."
  [relations ref]
  (build/relation-rows (env relations homes) ref))

(deftest relation-rows-test
  (testing "a single-role relation keeps every other member"
    (is (= [{:type    "syn"
             :members [{:headword "B" :file "b"}
                       {:headword "H" :file "h"}]}]
           (rows [{:type    "syn"
                   :members [{:ref "a"} {:ref "b"} {:ref "h"}]}]
                 "a"))))
  (testing "a multi-role relation drops the co-members of the own role"
    (let [relations [{:type    "hyp"
                      :members [{:ref "h" :role "hyper"}
                                {:ref "a" :role "hypo"}
                                {:ref "b" :role "hypo"}]}]]
      (is (= [{:type    "hyp"
               :role    "hyper"
               :members [{:headword "H" :file "h"}]}]
             (rows relations "a"))
          "a hyponym relates to the hypernym, not its co-hyponyms")
      (is (= [{:type    "hyp"
               :role    "hypo"
               :members [{:headword "A" :file "a"}
                         {:headword "B" :file "b"}]}]
             (rows relations "h"))
          "the hypernym relates to every hyponym")))
  (testing "relations sharing type and roles merge into one row"
    (is (= [{:type    "hyp"
             :role    "hypo"
             :members [{:headword "A" :file "a"}
                       {:headword "B" :file "b"}]}]
           (rows [{:type    "hyp"
                   :members [{:ref "h" :role "hyper"} {:ref "a" :role "hypo"}]}
                  {:type    "hyp"
                   :members [{:ref "h" :role "hyper"} {:ref "b" :role "hypo"}]}]
                 "h"))))
  (testing "an unresolvable ref is skipped"
    (is (= [] (rows [{:type "syn" :members [{:ref "a"} {:ref "gone"}]}]
                    "a"))))
  (testing "the instance description and the role description reach the row"
    (is (= [{:type            "hyp"
             :role            "hyper"
             :roleDescription "the broader concept"
             :note            "a curated note"
             :members         [{:headword "H" :file "h"}]}]
           (build/relation-rows
             (env [{:type        "hyp"
                    :description "a curated note"
                    :members     [{:ref "h" :role "hyper"}
                                  {:ref "a" :role "hypo"}]}]
                  homes
                  {"hyp" {:memberTypes [{:role        "hyper"
                                         :description "the broader concept"}]}})
             "a"))))
  (testing "a member whose memberType hints \"none\" is not shown"
    (is (= [] (build/relation-rows
                (env [{:type    "internal"
                       :members [{:ref "a"} {:ref "b" :role "hidden"}]}]
                     homes
                     {"internal" {:memberTypes [{:role "hidden"
                                                 :hint "none"}]}})
                "a")))))

(deftest member-ordering-test
  (testing "members keep the listing order of the dataset and carry :order"
    (is (= [{:headword "B" :file "b" :order 2}
            {:headword "A" :file "a"}]
           (->> (build/relation-rows
                  (env [{:type    "syn"
                         :members [{:ref "h"}
                                   {:ref "b" :obverseListingOrder 2}
                                   {:ref "a"}]}]
                       homes)
                  "h")
                (first)
                (:members))))))

(defn collated
  "The `members` of one relation row, by headword, under the Danish
  collation."
  [members]
  (let [collator (build/->collator "da")
        compare* (fn [a b] (.compare collator a b))]
    (->> (presentation/collate-members compare* {:relations [{:members members}]})
         (:relations)
         (first)
         (:members)
         (mapv :headword))))

(deftest collate-members-test
  (testing "members without an order sort by the collation of the language"
    (is (= ["bær" "æble" "ål"]
           (collated [{:headword "ål"} {:headword "æble"} {:headword "bær"}]))
        "æ and å sort after the plain letters in Danish"))
  (testing ":order wins over the collation"
    (is (= ["ål" "æble" "bær"]
           (collated [{:headword "æble" :order 2}
                      {:headword "bær" :order 3}
                      {:headword "ål" :order 1}]))))
  (testing "an unordered member sorts after every ordered one"
    (is (= ["ål" "bær" "æble"]
           (collated [{:headword "æble"}
                      {:headword "bær"}
                      {:headword "ål" :order 1}])))
    (is (= ["ål" "bær" "æble"]
           (collated [{:headword "æble" :order 7}
                      {:headword "bær" :order 7}
                      {:headword "ål" :order 1}]))
        "an equal order falls back to the collation")))

(deftest index-rows-test
  (let [resource {:langCode "da"
                  :entries  [{:id "åben" :headword "åben"}
                             {:id "abe" :headword "abe" :partsOfSpeech ["sb."]}
                             {:id "æble" :headword "æble"}]}]
    (is (= [["abe" "abe" "sb." nil]
            ["æble" (build/->file "æble") "" nil]
            ["åben" (build/->file "åben") "" nil]]
           (build/index-rows resource))
        "æ, ø and å sort after z with the Danish collation"))
  (testing "the pos column prefers the inventory description over the tag"
    (is (= [["abe" "abe" "substantiv" nil]]
           (build/index-rows
             {:partOfSpeechTags [{:tag "sb." :description "substantiv"}]
              :entries          [{:id            "abe"
                                  :headword      "abe"
                                  :partsOfSpeech ["sb."]}]})))))

(deftest manifest-test
  (is (= {:title     "Test"
          :entries   2
          :senses    3
          :relations 1}
         (build/manifest {:title     "Test"
                          :entries   [{:senses [{} {}]} {:senses [{}]}]
                          :relations [{}]}
                         nil)))
  (testing "the Dublin Core companion merges in, winning over the resource"
    (is (= {:title       "DanNet"
            :uri         "https://wordnet.dk/dannet/data/"
            :langCode    "da"
            :description "Det danske WordNet."
            :publisher   "CST"
            :rights      "© DSL & CST"
            :license     "https://creativecommons.org/licenses/by-sa/4.0/"
            :licenseName "CC BY-SA 4.0"
            :sources     [{:title       "COR"
                           :license     "https://creativecommons.org/publicdomain/zero/1.0/"
                           :licenseName "CC0 1.0"}
                          {:title       "DDS"
                           :full        "Det Danske Sentimentleksikon"
                           :license     "https://creativecommons.org/licenses/by-sa/4.0/"
                           :licenseName "CC BY-SA 4.0"}]
            :entries     0
            :senses      0
            :relations   0}
           (build/manifest
             {:title "ignored" :langCode "da"}
             {"dc:title"       "DanNet"
              "dc:identifier"  "https://wordnet.dk/dannet/data/"
              "dc:description" {"en" "The Danish WordNet."
                                "da" "Det danske WordNet."}
              "dc:publisher"   "CST"
              "dc:rights"      "© DSL & CST"
              "dc:license"     "https://creativecommons.org/licenses/by-sa/4.0/"
              "dc:source"      [{"dc:title"   "COR"
                                 "dc:license" "https://creativecommons.org/publicdomain/zero/1.0/"}
                                {"dc:title"   "DDS (Det Danske Sentimentleksikon)"
                                 "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"}]})))))

(deftest license-name-test
  (is (= "CC BY-SA 4.0"
         (build/license-name "https://creativecommons.org/licenses/by-sa/4.0/")))
  (is (= "CC BY 4.0"
         (build/license-name "https://creativecommons.org/licenses/by/4.0/")))
  (is (= "CC0 1.0"
         (build/license-name "https://creativecommons.org/publicdomain/zero/1.0/")))
  (testing "a license that is not Creative Commons keeps its URL"
    (is (nil? (build/license-name "https://example.com/license")))
    (is (nil? (build/license-name nil)))))

(deftest ->source-test
  (testing "an all-caps abbreviation splits from the parenthesized full name"
    (is (= {:title   "DDS"
            :full    "Det Danske Sentimentleksikon"
            :uri     "https://wordnet.dk/sentiment/"
            :license "https://example.com/by-sa"}
           (build/->source
             {"dc:title"      "DDS (Det Danske Sentimentleksikon)"
              "dc:identifier" "https://wordnet.dk/sentiment/"
              "dc:license"    "https://example.com/by-sa"}))))
  (testing "a plain title passes through unsplit"
    (is (= {:title "DanNet"} (build/->source {"dc:title" "DanNet"}))))
  (testing "every field is optional"
    (is (= {:title "COR" :uri "https://ordregister.dk"}
           (build/->source {"dc:title"      "COR"
                            "dc:identifier" "https://ordregister.dk"})))
    (is (= {:title "COR" :license "https://example.com/cc0"}
           (build/->source {"dc:title"   "COR"
                            "dc:license" "https://example.com/cc0"})))
    (is (= {} (build/->source {})))))

(defn temp-zip
  "A temporary zip file of the `entries` map of name -> content."
  [entries]
  (let [f (File/createTempFile "dmlex" ".zip")]
    (with-open [out (ZipOutputStream. (io/output-stream f))]
      (doseq [[name content] entries]
        (.putNextEntry out (ZipEntry. ^String name))
        (.write out (.getBytes ^String content "UTF-8"))
        (.closeEntry out)))
    (.deleteOnExit f)
    f))

(deftest ->input-test
  (testing "a zip export: the DMLex JSON is found among the companions"
    (let [f (temp-zip {"dict.xml"          "<xml/>"
                       "metadata.json"     "{\"dc:title\": \"T\"}"
                       "presentation.json" "{}"
                       "dict.json"         "{\"title\": \"T\"}"})
          {:keys [dmlex-file content-of]} (build/->input (str f))]
      (is (= "dict.json" dmlex-file))
      (is (= "{\"title\": \"T\"}" (content-of "dict.json")))
      (is (= {"dc:title" "T"} (build/read-companion content-of "metadata.json")))
      (is (nil? (content-of "missing.json")))))
  (testing "a zip with the files inside a folder"
    (let [f (temp-zip {"export/dict.json"     "{}"
                       "export/metadata.json" "{\"dc:title\": \"T\"}"})
          {:keys [dmlex-file content-of]} (build/->input (str f))]
      (is (= "dict.json" dmlex-file))
      (is (= "{\"dc:title\": \"T\"}" (content-of "metadata.json")))))
  (testing "a missing input throws instead of failing downstream"
    (is (thrown? clojure.lang.ExceptionInfo (build/->input "no-such.json"))))
  (testing "a plain JSON file reads its neighbours from its directory"
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                             (str "dmlex-test-" (System/nanoTime)))
                (.mkdirs))]
      (spit (io/file dir "dict.json") "{}")
      (spit (io/file dir "metadata.json") "{\"dc:title\": \"T\"}")
      (let [{:keys [dmlex-file content-of]}
            (build/->input (str (io/file dir "dict.json")))]
        (is (= "dict.json" dmlex-file))
        (is (= "{}" (content-of "dict.json")))
        (is (= "{\"dc:title\": \"T\"}" (content-of "metadata.json")))
        (is (nil? (content-of "missing.json")))))))

(deftest read-ui-test
  (testing "a ui.po next to the DMLex file wins over the config table"
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                             (str "dmlex-ui-" (System/nanoTime)))
                (.mkdirs))]
      (spit (io/file dir "dict.json") "{}")
      (spit (io/file dir "ui.po")
            (str "msgid \"\"\nmsgstr \"\"\n"
                 "\"Content-Type: text/plain; charset=UTF-8\\n\"\n"
                 "\n"
                 "msgid \"all forms\"\nmsgstr \"alle former\"\n"))
      (let [{:keys [content-of]} (build/->input (str (io/file dir "dict.json")))]
        (is (= {"all forms" "alle former"}
               (build/read-ui content-of nil)))
        (is (= {"all forms" "alle former"
                "label"     "etiket"}
               (build/read-ui content-of {"ui" {"all forms" "gammel"
                                                "label"     "etiket"}})))))))

(def resource
  "A minimal DMLex resource exercising every inventory."
  {:title              "Test"
   :langCode           "da"
   :labelTags          [{:tag         "zoo"
                         :description "zoology"
                         :typeTag     "domain"
                         :sameAs      ["https://example.com/zoo"]}]
   :labelTypeTags      [{:tag         "domain"
                         :description "subject domain"
                         :sameAs      ["https://example.com/domain"]}]
   :definitionTypeTags [{:tag "short" :description "short definition"}]
   :partOfSpeechTags   [{:tag         "sb."
                         :description "substantiv"
                         :sameAs      ["https://example.com/sb"]}]
   :inflectedFormTags  [{:tag "pl" :description "plural"}]
   :sourceIdentityTags [{:tag         "DDO"
                         :description "Den Danske Ordbog"
                         :sameAs      ["https://ordnet.dk/ddo"]}]
   :relationTypes      [{:type        "syn"
                         :description "synonymy"
                         :sameAs      ["https://example.com/syn"]}]
   :relations          [{:type "syn" :members [{:ref "s1"} {:ref "s2"}]}]
   :entries            [{:id             "hund"
                         :headword       "hund"
                         :partsOfSpeech  ["sb."]
                         :labels         ["zoo"]
                         :inflectedForms [{:tag "pl" :text "hunde"}]
                         :senses         [{:id                   "s1"
                                           :headwordTranslations [{:text     "dog"
                                                                   :langCode "en"}
                                                                  {:text     "hound"
                                                                   :langCode "en"}]
                                           :definitions [{:text           "et dyr"
                                                          :definitionType "short"}]
                                           :examples    [{:text            "en stor hund"
                                                          :sourceIdentity  "DDO"
                                                          :labels          ["zoo"]
                                                          :headwordMarkers [{:startIndex 8
                                                                             :endIndex   12}]}]}]}
                        {:id       "køter"
                         :headword "køter"
                         :senses   [{:id        "s2"
                                     :indicator "nedsættende"}]}]})

(deftest ->entry-file-test
  (let [env   (build/->env resource)
        entry (build/->entry-file env (first (:entries resource)))]
    (testing "tags resolve through every inventory"
      (is (= [{:tag         "sb."
               :description "substantiv"
               :uri         "https://example.com/sb"}]
             (:partsOfSpeech entry)))
      (is (= [{:tag             "zoo"
               :type            "domain"
               :description     "zoology"
               :typeDescription "subject domain"
               :typeUri         "https://example.com/domain"
               :uri             "https://example.com/zoo"}]
             (:labels entry)))
      (is (= [{:tag "pl" :description "plural" :text "hunde" :short "-e"}]
             (:inflectedForms entry))))
    (testing "headword translations pass through with their language"
      (is (= [{:text "dog" :lang "en"} {:text "hound" :lang "en"}]
             (:translations (first (:senses entry))))))
    (testing "a definition resolves its type"
      (is (= [{:text            "et dyr"
               :type            "short"
               :typeDescription "short definition"}]
             (:definitions (first (:senses entry))))))
    (testing "an example resolves its source, its labels and its marker runs"
      (is (= [{:text              "en stor hund"
               :runs              [{:text "en stor "}
                                   {:text "hund" :marker "headword"}]
               :labels            [{:tag             "zoo"
                                    :type            "domain"
                                    :description     "zoology"
                                    :typeDescription "subject domain"
                                    :typeUri         "https://example.com/domain"
                                    :uri             "https://example.com/zoo"}]
               :source            "DDO"
               :sourceDescription "Den Danske Ordbog"
               :sourceUri         "https://ordnet.dk/ddo"}]
             (:examples (first (:senses entry))))))
    (testing "a sense carries the pre-resolved rows of its relations"
      (is (= [{:type        "syn"
               :description "synonymy"
               :uri         "https://example.com/syn"
               :members     [{:headword  "køter"
                              :file      (build/->file "køter")
                              :indicator "nedsættende"}]}]
             (:relations (first (:senses entry))))))
    (testing "an entry without relations of its own has no :relations key"
      (is (not (contains? entry :relations))))))

(deftest text-runs-test
  (testing "text without markers has no runs"
    (is (nil? (build/text-runs "en stor hund" nil nil))))
  (testing "a headword marker splits the text into runs"
    (is (= [{:text "en stor "}
            {:text "hund" :marker "headword"}
            {:text " gør"}]
           (build/text-runs "en stor hund gør"
                            [{:startIndex 8 :endIndex 12}] nil))))
  (testing "a collocate marker keeps its lemma"
    (is (= [{:text "hunden "}
            {:text "gør" :marker "collocate" :lemma "gø"}]
           (build/text-runs "hunden gør" nil
                            [{:startIndex 7 :endIndex 10 :lemma "gø"}]))))
  (testing "a marker overlapping an earlier one is ignored"
    (is (= [{:text "abcd" :marker "headword"} {:text "ef"}]
           (build/text-runs "abcdef"
                            [{:startIndex 0 :endIndex 4}]
                            [{:startIndex 2 :endIndex 6}]))))
  (testing "a marker outside the text is ignored"
    (is (= [{:text "kort"}]
           (build/text-runs "kort" [{:startIndex 3 :endIndex 99}] nil)))))
