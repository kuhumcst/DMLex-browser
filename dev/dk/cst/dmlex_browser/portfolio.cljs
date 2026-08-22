(ns dk.cst.dmlex-browser.portfolio
  "The runner of the scene workbench: npx shadow-cljs watch portfolio,
  then http://localhost:8001.

  The scenes live in dk.cst.dmlex-browser.views-scenes and render into
  dev-resources/public/canvas.html, which gives each one the app's
  own page sheet and stylesheet. So a markup or CSS edit can be judged
  against the awkward DMLex shapes without a dataset to hand."
  (:require [portfolio.replicant :as replicant]
            [portfolio.ui :as portfolio]))

(defn ^:dev/after-load start
  "Start the Portfolio UI over the registered scenes."
  []
  (replicant/set-render-options! {:alias-data {:ui nil}})
  (portfolio/start! {:config {:canvas-path "/canvas.html"}}))

(start)
