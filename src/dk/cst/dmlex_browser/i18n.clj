(ns dk.cst.dmlex-browser.i18n
  "Gettext tooling for the translatable UI chrome of the project.

  Extracts every key the views pass to the hiccup/tr alias, to tr or
  to en into the template i18n/template.pot, which translators load
  into a PO tool such as Poedit to produce the ui.po a dataset ships
  next to its DMLex file. The template is regenerated from the source
  with clojure -M:i18n and a test compares it against a fresh
  extraction, so it cannot drift.

  A key argument must be a string literal, a (str ...) of literals, or
  a symbol bound in the enclosing let to a conditional over such
  values; anything else is invisible to the extraction."
  (:require [clojure.java.io :as io]
            [edamame.core :as e]
            [pottery.po :as po]
            [pottery.scan :as scan]))

(def source-files
  "The namespaces whose chrome strings the template carries."
  ["src/dk/cst/dmlex_browser/views.cljc"
   "src/dk/cst/dmlex_browser/build.clj"
   "src/dk/cst/dmlex_browser/appledict.clj"])

(def template-file
  "The gettext template of the UI chrome, committed with the source."
  "i18n/template.pot")

(defn parse-forms
  "Parse the source `file` into its top-level forms, tolerating cljs
  reader syntax the way the pottery scanner does."
  [file]
  (e/parse-string-all (slurp file)
                      {:all          true
                       :readers      (fn [_] identity)
                       :read-cond    :allow
                       :features     #{:clj :cljs}
                       :regex        #(list `re-pattern %)
                       :auto-resolve symbol}))

(defn ui-key-arg
  "The key argument of the tr/en call or of the hiccup/tr alias in
  `form`, or nil for any other form.

  The alias carries its string as the last element, after the attribute
  map: [hiccup/tr {:hiccup/tag :dt} \"publisher\"]."
  [form]
  (cond
    (and (vector? form) (= 'hiccup/tr (first form)))
    (last form)

    (seq? form)
    (case (first form)
      (tr en)               (second form)
      (shared/tr shared/en) (nth form 2 nil)
      nil)))

(defn literals
  "Every UI string of the key expression `x`.

  A literal is itself, a (str ...) of literals joins into one, and the
  branches of a conditional each stand on their own."
  [x]
  (cond
    (string? x)                       [x]
    (and (seq? x) (= 'str (first x))) [(apply str (filter string? (rest x)))]
    (coll? x)                         (mapcat literals (seq x))
    :else                             []))

(defn extract
  "The UI keys of `form`: the arguments of its tr and en calls.

  A key argument is a literal, or a symbol bound in the enclosing let,
  so a view can bind one key for both its text and its lang attribute."
  [form]
  (let [arg (ui-key-arg form)]
    (cond
      (and arg (not (symbol? arg)))
      (literals arg)

      (and (seq? form) (= 'let (first form)) (vector? (second form)))
      (let [body  (drop 2 form)
            used? (fn [sym]
                    (some #(= sym (ui-key-arg %))
                          (tree-seq coll? seq body)))]
        (for [[sym v] (partition 2 (second form))
              :when   (and (simple-symbol? sym) (used? sym))
              s       (literals v)]
          s)))))

(defn extracted-keys
  "Every translatable UI string of the `files`, in order of appearance."
  [files]
  (->> files
       (mapcat (fn [file]
                 (mapcat extract (tree-seq coll? seq (parse-forms file)))))
       (distinct)
       (vec)))

(def header
  "The gettext header block that opens the template.

  The PO reader of pottery skips the first block of a file, so the
  template and every ui.po must open with one."
  "msgid \"\"\nmsgstr \"\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\n")

(defn scan!
  "Write the gettext template of the UI chrome to `template-file`."
  []
  (let [results (for [file source-files]
                  {::scan/filename    file
                   ::scan/expressions (for [k (extracted-keys [file])]
                                        {::scan/value k})})]
    (io/make-parents template-file)
    (spit template-file (str header (po/gen-template results) "\n"))
    (println "Wrote" template-file)))

(defn -main
  "Regenerate the template from the command line: clojure -M:i18n"
  [& _]
  (scan!)
  (shutdown-agents))
