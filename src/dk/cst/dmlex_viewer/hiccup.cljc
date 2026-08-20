(ns dk.cst.dmlex-viewer.hiccup
  "The hiccup vocabulary of the views: a translation alias, a link alias
  and the paths that the second one builds.

  Both aliases read what they need from Replicant's alias data, so no
  view has to carry the UI table or the URL scheme as an argument. The
  render call supplies the table under :ui; see dk.cst.dmlex-viewer.app
  for the browser and dk.cst.dmlex-viewer.build for the pre-rendered
  pages."
  (:require [dk.cst.dmlex-viewer.shared :as shared]
            [replicant.alias :refer [defalias]]))

(def front-path
  "The site-relative path of the front page."
  "./")

(defn entry-path
  "The site-relative path of the entry file basename `file`, targeting
  its sense `sense` when one is named."
  ([file]
   (str "entry/" file "/"))
  ([file sense]
   (cond-> (entry-path file)
     sense (str "#" sense))))

(defalias tr
  "The UI string of the child, translated by the table in the alias
  data and rendered in `:hiccup/tag` (a span by default).

  An untranslated string keeps its lang=\"en\" marker for assistive
  technology, and `:hiccup/n` fills the {n} placeholder of a template."
  [{:hiccup/keys [tag n] :replicant/keys [alias-data] :as attrs} [s]]
  (let [ui (:ui alias-data)]
    [(or tag :span)
     (cond-> attrs
       (shared/en ui s) (assoc :lang "en"))
     (if n
       (shared/tr ui s n)
       (shared/tr ui s))]))

(defalias a
  "A link to the entry file basename `:hiccup/entry` — targeting its
  sense `:hiccup/sense` when one is named — or to the front page when
  neither is given."
  [{:hiccup/keys [entry sense] :as attrs} children]
  (into [:a (assoc attrs :href (if entry
                                 (entry-path entry sense)
                                 front-path))]
        children))
