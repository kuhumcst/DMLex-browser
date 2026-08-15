(ns dk.cst.dmlex-viewer.build-test
  "Tests of the pure resolution logic of the data build."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-viewer.build :as build]))

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
  (testing "nil when the reduction would mislead"
    (is (nil? (build/affix "slå op" "slog op")) "multiword headword")
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
  [relations homes*]
  {:collator    (build/->collator "da")
   :relations   relations
   :reltype-of  {}
   :resolve-ref homes*
   :ref->idxs   (build/member-refs relations)})

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
                    "a")))))

(def collated-homes
  {"h" {:headword "H" :file "h"}
   "æ" {:headword "æble" :file "æ"}
   "å" {:headword "ål" :file "å"}
   "b" {:headword "bær" :file "b"}})

(defn collated-rows
  "The members of the one relation row of `ref`, by headword."
  [relations ref]
  (->> (build/relation-rows (env relations collated-homes) ref)
       (first)
       (:members)
       (mapv :headword)))

(deftest member-ordering-test
  (testing "members without an order sort by the collation of the language"
    (is (= ["bær" "æble" "ål"]
           (collated-rows [{:type    "syn"
                            :members [{:ref "h"} {:ref "å"} {:ref "æ"}
                                      {:ref "b"}]}]
                          "h"))
        "æ and å sort after the plain letters in Danish"))
  (testing "obverseListingOrder wins over the collation"
    (is (= ["ål" "æble" "bær"]
           (collated-rows [{:type    "syn"
                            :members [{:ref "h"}
                                      {:ref "å" :obverseListingOrder 1}
                                      {:ref "æ" :obverseListingOrder 2}
                                      {:ref "b" :obverseListingOrder 3}]}]
                          "h"))))
  (testing "an unordered member sorts after every ordered one"
    (is (= ["ål" "bær" "æble"]
           (collated-rows [{:type    "syn"
                            :members [{:ref "h"}
                                      {:ref "æ"}
                                      {:ref "b"}
                                      {:ref "å" :obverseListingOrder 1}]}]
                          "h")))
    (is (= ["ål" "bær" "æble"]
           (collated-rows [{:type    "syn"
                            :members [{:ref "h"}
                                      {:ref "æ" :obverseListingOrder 7}
                                      {:ref "b" :obverseListingOrder 7}
                                      {:ref "å" :obverseListingOrder 1}]}]
                          "h"))
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
        "æ, ø and å sort after z with the Danish collation")))

(deftest manifest-test
  (is (= {:title     "Test"
          :entries   2
          :senses    3
          :relations 1}
         (build/manifest {:title     "Test"
                          :entries   [{:senses [{} {}]} {:senses [{}]}]
                          :relations [{}]}))))

(def resource
  "A minimal DMLex resource exercising every inventory."
  {:title              "Test"
   :langCode           "da"
   :labelTags          [{:tag         "zoo"
                         :description "zoology"
                         :typeTag     "domain"
                         :sameAs      ["https://example.com/zoo"]}]
   :labelTypeTags      [{:tag "domain" :description "subject domain"}]
   :partOfSpeechTags   [{:tag "sb." :description "substantiv"}]
   :inflectedFormTags  [{:tag "pl" :description "plural"}]
   :sourceIdentityTags []
   :relationTypes      [{:type "syn" :description "synonymy"}]
   :relations          [{:type "syn" :members [{:ref "s1"} {:ref "s2"}]}]
   :entries            [{:id             "hund"
                         :headword       "hund"
                         :partsOfSpeech  ["sb."]
                         :labels         ["zoo"]
                         :inflectedForms [{:tag "pl" :text "hunde"}]
                         :senses         [{:id          "s1"
                                           :definitions [{:text "et dyr"}]}]}
                        {:id       "køter"
                         :headword "køter"
                         :senses   [{:id        "s2"
                                     :indicator "nedsættende"}]}]})

(deftest ->entry-file-test
  (let [env   (build/->env resource)
        entry (build/->entry-file env (first (:entries resource)))]
    (testing "tags resolve through every inventory"
      (is (= [{:tag "sb." :description "substantiv"}]
             (:partsOfSpeech entry)))
      (is (= [{:tag             "zoo"
               :type            "domain"
               :description     "zoology"
               :typeDescription "subject domain"
               :uri             "https://example.com/zoo"}]
             (:labels entry)))
      (is (= [{:tag "pl" :description "plural" :text "hunde" :short "-e"}]
             (:inflectedForms entry))))
    (testing "a sense carries the pre-resolved rows of its relations"
      (is (= [{:type        "syn"
               :description "synonymy"
               :members     [{:headword  "køter"
                              :file      (build/->file "køter")
                              :indicator "nedsættende"}]}]
             (:relations (first (:senses entry))))))
    (testing "an entry without relations of its own has no :relations key"
      (is (not (contains? entry :relations))))))
