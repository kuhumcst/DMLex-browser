(ns dk.cst.dmlex-viewer.presentation-test
  "Tests of the presentation ops, run by both the JVM and the node test
  suites since the ops serve both display surfaces."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dmlex-viewer.presentation :as presentation]
            [dk.cst.dmlex-viewer.shared :as shared]))

(def labels
  [{:tag "a1" :type "alpha"}
   {:tag "b1" :type "beta"}
   {:tag "c1" :type "gamma"}
   {:tag "a2" :type "alpha"}])

(deftest present-test
  (testing "nil ops only normalize to a vector"
    (is (= labels (presentation/present nil :type labels))))
  (testing "hide removes every label of the type"
    (is (= ["b1" "c1"]
           (map :tag (presentation/present {"hide" ["alpha"]} :type labels)))))
  (testing "order lists first; the unlisted follow in their own order"
    (is (= ["c1" "a1" "a2" "b1"]
           (map :tag (presentation/present {"order" ["gamma" "alpha"]}
                                           :type labels)))))
  (testing "hide beats order"
    (is (= ["c1" "b1"]
           (map :tag (presentation/present {"order" ["gamma" "alpha"]
                                            "hide"  ["alpha"]}
                                           :type labels)))))
  (testing "unlisted \"hide\" turns order into an allowlist"
    (is (= ["c1"]
           (map :tag (presentation/present {"order"    ["gamma"]
                                            "unlisted" "hide"}
                                           :type labels)))))
  (testing "rename attaches the display name of the key"
    (is (= [nil "B" nil nil]
           (map :display
                (presentation/present {"rename" {"beta" "B"}}
                                      :type labels))))))

(deftest show-labels-test
  (testing "the face and the tooltip switch places"
    (is (= [{:tag "{hund; køter}" :type "synset" :description "synset-1"}]
           (presentation/show-labels {"synset" "description"}
                                     [{:tag         "synset-1"
                                       :type        "synset"
                                       :description "{hund; køter}"}]))))
  (testing "a label without a description keeps its tag"
    (is (= [{:tag "synset-1" :type "synset"}]
           (presentation/show-labels {"synset" "description"}
                                     [{:tag "synset-1" :type "synset"}])))))

(def sentiments
  [{:tag "Neutral" :type "pol"}
   {:tag "0" :type "val"}
   {:tag "b1" :type "beta"}])

(deftest combine-labels-test
  (testing "a host absorbs its qualifier's values"
    (is (= [{:tag "Neutral" :type "pol" :qualifier "0"}
            {:tag "b1" :type "beta"}]
           (presentation/combine-labels {"pol" "val"} sentiments))))
  (testing "a qualifier without a host stays an ordinary label"
    (is (= [{:tag "0" :type "val"}]
           (presentation/combine-labels {"pol" "val"}
                                        [{:tag "0" :type "val"}]))))
  (testing "combining applies before the other ops"
    (is (= [["Neutral" "0"]]
           (map (juxt :tag :qualifier)
                (:labels (presentation/present-entry
                           {"labelTypes" {"order"    ["pol"]
                                          "unlisted" "hide"
                                          "combine"  {"pol" "val"}}}
                           {:labels sentiments})))))))

(deftest inline-labels-test
  (testing "listed types move to :inline-labels in the listed order"
    (is (= {:inline-labels [{:tag "b1" :type "beta"}
                            {:tag "a1" :type "alpha"}
                            {:tag "a2" :type "alpha"}]
            :labels        [{:tag "c1" :type "gamma"}]}
           (presentation/inline-labels ["beta" "alpha"] {:labels labels}))))
  (testing "an entry whose labels all inline drops :labels"
    (is (= {:inline-labels [{:tag "c1" :type "gamma"}]}
           (presentation/inline-labels
             ["gamma"] {:labels [{:tag "c1" :type "gamma"}]}))))
  (testing "without a matching type the entry passes through"
    (is (= {:labels labels}
           (presentation/inline-labels nil {:labels labels}))))
  (testing "present-entry inlines only the entry's labels, after the ops"
    (let [e (presentation/present-entry
              {"labelTypes" {"inline"  ["pol"]
                             "combine" {"pol" "val"}}}
              {:labels sentiments
               :senses [{:labels sentiments}]})]
      (is (= [{:tag "Neutral" :type "pol" :qualifier "0"}]
             (:inline-labels e))
          "the combined qualifier carries over")
      (is (= [{:tag "b1" :type "beta"}] (:labels e))
          "other labels stay in the block")
      (is (nil? (get-in e [:senses 0 :inline-labels]))
          "a sense has no part-of-speech line to inline onto")))
  (testing "hide beats inline"
    (is (nil? (:inline-labels
                (presentation/present-entry
                  {"labelTypes" {"inline" ["pol"] "hide" ["pol"]}}
                  {:labels sentiments}))))))

(def rel-rows
  [{:type "hypernym" :members []}
   {:type "similar" :members []}
   {:type "also" :members []}
   {:type "hyponym" :members []}])

(deftest group-relations-test
  (testing "groups render in listed order, rows by position in types"
    (is (= [["a" ["hyponym" "hypernym"]] ["b" ["similar"]] [nil ["also"]]]
           (map (juxt :title #(map :type (:relations %)))
                (presentation/group-relations
                  [{"title" "a" "types" ["hyponym" "hypernym"]}
                   {"title" "b" "types" ["similar"]}]
                  nil rel-rows)))
        "unclaimed rows trail in an untitled group"))
  (testing "a group without types is the fallback"
    (is (= [["a" ["hypernym"]] ["rest" ["similar" "also" "hyponym"]]]
           (map (juxt :title #(map :type (:relations %)))
                (presentation/group-relations
                  [{"title" "a" "types" ["hypernym"]}
                   {"title" "rest"}]
                  nil rel-rows)))))
  (testing "unlisted \"hide\" drops unclaimed rows; empty groups disappear"
    (is (= [["a" ["hypernym"]]]
           (map (juxt :title #(map :type (:relations %)))
                (presentation/group-relations
                  [{"title" "a" "types" ["hypernym"]}
                   {"title" "empty" "types" ["gone"]}]
                  "hide" rel-rows)))))
  (testing "present-entry swaps :relations for :relation-groups"
    (let [e (presentation/present-entry
              {"relationTypes" {"groups" [{"title" "a"
                                           "types" ["hypernym"]}]}}
              {:relations rel-rows})]
      (is (nil? (:relations e)))
      (is (= "a" (:title (first (:relation-groups e))))))))

(def config
  {"labelTypes"    {"order"    ["gamma" "alpha"]
                    "unlisted" "hide"
                    "rename"   {"gamma" "Γ"}}
   "relationTypes" {"order" ["syn"]}
   "roles"         {"rename" {"hyper" "over"}}})

(def entry
  {:headword  "x"
   :labels    labels
   :relations [{:type "hyp" :role "hyper" :members []}
               {:type "syn" :members []}]
   :senses    [{:labels    [{:tag "b2" :type "beta"}]
                :relations [{:type "hyp" :role "hypo" :members []}]}
               {:indicator "bare"}]})

(deftest present-entry-test
  (testing "an empty config is the identity"
    (is (= entry (presentation/present-entry nil entry)))
    (is (= entry (presentation/present-entry {} entry))))
  (let [e (presentation/present-entry config entry)]
    (testing "entry labels are ordered, filtered and renamed"
      (is (= [["c1" "Γ"] ["a1" nil] ["a2" nil]]
             (map (juxt :tag :display) (:labels e)))))
    (testing "relation rows are ordered and roles renamed"
      (is (= [["syn" nil] ["hyp" "over"]]
             (map (juxt :type :display-role) (:relations e)))))
    (testing "senses get the same treatment"
      (is (nil? (get-in e [:senses 0 :labels]))
          "a sense whose labels are all hidden shows none")
      (is (= "hypo" (get-in e [:senses 0 :relations 0 :role]))
          "a role without a rename keeps its name")
      (is (= {:indicator "bare"} (get-in e [:senses 1]))
          "a sense without labels or relations passes through"))))

(deftest collate-members-test
  (testing "every relation row of the entry, its senses and its groups sorts"
    (is (= {:relations       [{:members [{:headword "a" :order 1}
                                         {:headword "b"}]}]
            :senses          [{:relations [{:members [{:headword "b"}
                                                      {:headword "c"}]}]}]
            :relation-groups [{:relations [{:members [{:headword "y"}
                                                      {:headword "z"}]}]}]}
           (presentation/collate-members
             (shared/member-order compare)
             {:relations       [{:members [{:headword "b"}
                                           {:headword "a" :order 1}]}]
              :senses          [{:relations [{:members [{:headword "c"}
                                                        {:headword "b"}]}]}]
              :relation-groups [{:relations [{:members [{:headword "z"}
                                                        {:headword "y"}]}]}]})))))

(deftest resolve-links-test
  (testing "sameAs-derived URIs route through the resolver, encoded"
    (is (= {:labels [{:tag     "zoo"
                      :uri     "https://home.org/browse?subject=https%3A%2F%2Fvocab.org%2Fx%23y"
                      :typeUri "https://home.org/browse?subject=https%3A%2F%2Fvocab.org%2Ft"}]
            :senses [{:examples [{:sourceUri "https://home.org/browse?subject=https%3A%2F%2Fsrc.org%2Fddo"}]}]}
           (presentation/resolve-links
             "https://home.org/browse?subject="
             {:labels [{:tag     "zoo"
                        :uri     "https://vocab.org/x#y"
                        :typeUri "https://vocab.org/t"}]
              :senses [{:examples [{:sourceUri "https://src.org/ddo"}]}]}))))
  (testing "a URI on the resolver's own host links directly"
    (is (= {:relations [{:uri "https://home.org/data/syn"}]}
           (presentation/resolve-links
             "https://home.org/browse?subject="
             {:relations [{:uri "https://home.org/data/syn"}]}))))
  (testing "present-entry applies the config's linkResolver"
    (is (= {:labels [{:tag  "a1"
                      :type "alpha"
                      :uri  "https://home.org/browse?subject=https%3A%2F%2Fvocab.org%2Fa"}]}
           (presentation/present-entry
             {"linkResolver" "https://home.org/browse?subject="}
             {:labels [{:tag "a1" :type "alpha" :uri "https://vocab.org/a"}]})))))
