(ns dk.cst.dmlex-viewer.translations
  "The bundled UI translations of the viewer, read from the po files
  in i18n/.

  The web viewer inlines the tables at compile time via the
  inline-tables macro and picks one by the langCode of the manifest;
  the Apple dictionary export reads them at export time. A dataset's
  own \"ui\" section or ui.po merges over the bundled table, so a
  dataset can still override or extend the chrome. After editing a po
  file, force a recompile of the frontend: the ClojureScript build
  cannot see through the macro to the file."
  (:require [clojure.java.io :as io]
            [pottery.core :as pottery]))

(def po-files
  "The bundled translations: language code -> po file."
  {"da" "i18n/da.po"})

(defn tables
  "The bundled translation tables, language code -> ui table."
  []
  (update-vals po-files (comp pottery/read-po-file io/file)))

(defmacro inline-tables
  "The bundled tables as a literal, for the ClojureScript build."
  []
  (tables))
