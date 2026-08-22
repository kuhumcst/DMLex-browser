(ns dk.cst.dmlex-browser.views-scenes
  "Scenes of the views over hand-made DMLex shapes.

  One scene per shape that a real dataset produces rarely and that the
  markup has to survive: a qualified inline label, a relation row long
  enough to fold, a paradigm of variant spellings, a homograph group."
  (:require [dk.cst.dmlex-browser.views :as views]
            [portfolio.replicant :refer [defscene]]))

(def sense
  "A sense with one of everything the sense view renders."
  {:id           "hund-1"
   :indicator    "dyret"
   :definitions  [{:text "et tamt rovdyr" :type "def"}]
   :examples     [{:text   "en stor hund"
                   :runs   [{:text "en stor "}
                            {:text "hund" :marker "headword"}]
                   :labels [{:tag "talespr." :description "talesprog"}]}
                  {:text              "Hunden gøede."
                   :source            "DDO"
                   :sourceDescription "Den Danske Ordbog"
                   :sourceElaboration "https://ordnet.dk/ddo/hund"}]
   :labels       [{:tag "zoo" :type "domain" :description "zoologi"
                   :typeDescription "fagområde"}
                  {:tag "fk." :type "gender" :display "køn"}]
   :translations [{:lang "en" :text "dog"} {:lang "en" :text "hound"}
                  {:lang "de" :text "Hund"}]
   :relations    [{:type "hypernym" :role "super" :members
                   [{:headword "dyr" :file "dyr" :sense "dyr-1"}
                    {:headword "pattedyr" :file "pattedyr"}]}]})

(def entry
  "A full entry: parts of speech, an inline label with a qualifier,
  inflected forms with and without reduced shorts, two senses."
  {:file            "hund"
   :headword        "hund"
   :homographNumber "1"
   :partsOfSpeech   [{:tag "sb." :description "substantiv"}]
   :inline-labels   [{:tag "positiv" :type "sentiment" :display "valør"
                      :qualifier "1"}]
   :labels          [{:tag "almen" :type "brug" :display "brug"}]
   :inflectedForms  [{:tag "sg-def" :text "hunden" :short "-en"
                      :description "bestemt ental"}
                     {:tag "sg-def" :text "hund-en"}
                     {:tag "pl" :text "hunde" :short "-e"
                      :description "ubestemt flertal"
                      :labels [{:tag "sj." :description "sjældent"}]}]
   :senses          [sense
                     {:id          "hund-2"
                      :definitions [{:text "en usling"}]}]})

(def long-relation
  "A relation row past the ten members that fold it into a disclosure."
  {:type    "synonym"
   :role    "syn"
   :members (for [i (range 14)]
              {:headword (str "ord-" i) :file (str "ord-" i)})})

(defscene entry-view
  :title "An entry"
  (views/entry-view nil {:spy "hund-1"} entry))

(defscene entry-view-navigated
  :title "An entry, sense navigated to"
  (views/entry-view nil {:current "hund-2" :spy "hund-2"} entry))

(defscene single-sense-entry
  :title "An entry of one sense"
  (views/entry-view nil {} (update entry :senses (comp vector first))))

(defscene homograph-group
  :title "A homograph group"
  (views/entries-view nil {:spy "hund-1"}
                      [entry
                       (assoc entry :file "hund-2" :homographNumber "2"
                              :senses [{:id "hund-3"
                                        :definitions [{:text "et redskab"}]}])]))

(defscene folded-relation
  :title "A relation row that folds"
  (views/relations-dl [long-relation]))

(defscene grouped-relations
  :title "Titled relation groups"
  (views/relations-view [:nav.related]
                        nil
                        [{:title       "Overbegreber"
                          :description "Bredere betydninger"
                          :relations   [(first (:relations sense))]}
                         {:relations [long-relation]}]))

(defscene paradigm
  :title "A paradigm of variant spellings"
  (views/paradigm-view (:inflectedForms entry)))

(defscene sense-index
  :title "The sense index"
  (views/index-disclosure nil {:spy "hund-2"} [entry]))

(defscene search-results
  :title "Search results"
  (views/results-view nil
                      [{:headword "hund" :file "hund" :pos "sb."}
                       {:headword "hundehus" :file "hundehus" :pos "sb."}]
                      "hund"
                      0))

(defscene front-matter
  :title "The front matter"
  (views/front-matter-view
    {:description "Et lille eksempel på en DMLex-ressource."
     :publisher   "Center for Sprogteknologi"
     :rights      "© CST"
     :license     "https://creativecommons.org/licenses/by-sa/4.0/"
     :licenseName "CC BY-SA 4.0"
     :sources     [{:title "DDO" :full "Den Danske Ordbog"
                    :uri   "https://ordnet.dk/ddo"
                    :license "https://creativecommons.org/licenses/by-sa/4.0/"
                    :licenseName "CC BY-SA 4.0"}]}))
