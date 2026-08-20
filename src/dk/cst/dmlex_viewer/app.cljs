(ns dk.cst.dmlex-viewer.app
  "A generic DMLex viewer: one search field over the entry index and a
  typography-first rendering of single entries.

  The app is a static site. It fetches the data files that
  dk.cst.dmlex-viewer.build writes: manifest.json, index.json and one
  pre-resolved file per entry. The same build pre-renders every page,
  so this namespace holds only what a browser adds to it: the state,
  the effects behind the actions the views dispatch, and the routing."
  (:require [clojure.string :as str]
            [dk.cst.dmlex-viewer.presentation :as presentation]
            [dk.cst.dmlex-viewer.shared :as shared]
            [dk.cst.dmlex-viewer.hiccup :as hiccup]
            [dk.cst.dmlex-viewer.views :as views]
            [replicant.dom :as r])
  (:require-macros [dk.cst.dmlex-viewer.translations :refer [inline-tables]]))

(defonce state
  (atom {:manifest      nil
         :presentation  nil
         :index         nil
         :index-error   nil
         :query         ""
         :active        nil
         :raw-entries   nil
         :entries       nil
         :nav           {}
         :folded        #{}
         :error         nil
         :routed?       false
         :alpha?        false
         :presentation? true
         :lang          nil}))

(def translations
  "The bundled UI tables by language code, inlined from i18n/*.po."
  (inline-tables))

(def ui-languages
  "The offered UI languages: the bundled tables plus English."
  (sort (conj (set (keys translations)) "en")))

(defn fetch-json
  "The parsed content of the JSON file at `path`, as a js/Promise."
  [path]
  (-> (js/fetch path)
      (.then (fn [res]
               (if (.-ok res)
                 (.json res)
                 (throw (js/Error. (str path ": " (.-status res)))))))
      (.then #(js->clj % :keywordize-keys true))))

(defn fetch-json!
  "Fetch the JSON file at `path` and call `callback` with its parsed
  content.

  When the fetch fails, call `on-error` with the error; by default this
  puts the error message into the app state."
  ([path callback]
   (fetch-json! path callback
                (fn [e] (swap! state assoc :error (.-message e)))))
  ([path callback on-error]
   (-> (fetch-json path)
       (.then callback)
       (.catch (fn [e]
                 (js/console.error e)
                 (on-error e))))))

(defn fetch-entry
  "The entry of the file basename `file`, as a js/Promise of the parsed
  map."
  [file]
  (fetch-json (str "data/entries/" file ".json")))

(defn collapse-homographs
  "One search row per headword and part of speech over the index
  `rows`, keeping the first row of each group.

  The merged entry pages make the other group members reachable, so
  the suggestions need no numbering."
  [rows]
  (shared/distinct-by (juxt :headword :pos) rows))

(defn load-index!
  "Fetch the search index, collapse its homograph groups and cache a
  lowercase headword on every row.

  A failure lands in :index-error rather than :error, so the search view
  can surface it even while an entry is on screen."
  []
  (fetch-json! "data/index.json"
               (fn [rows]
                 (swap! state assoc :index
                        (->> rows
                             (mapv (fn [[headword file pos]]
                                     {:headword headword
                                      :lower    (str/lower-case headword)
                                      :file     file
                                      :pos      pos}))
                             (collapse-homographs))))
               (fn [e]
                 (swap! state assoc :index-error (.-message e)))))

(defn load-presentation!
  "Fetch the optional presentation config next to the data.

  Its keys are the dataset's own tags, so they stay strings rather than
  keywords. A missing file is the normal case and leaves the state
  untouched."
  []
  (-> (js/fetch "data/presentation.json")
      (.then (fn [res] (when (.-ok res) (.json res))))
      (.then (fn [data]
               (when data
                 (swap! state #(-> %
                                   (assoc :presentation (js->clj data))
                                   (presentation/present-state))))))
      (.catch (fn [_] nil))))

(defn site-path
  "The site-relative path of the absolute URL `url`, or nil when it
  points outside the site.

  Every page carries a base element at the site root, so the base URI
  is exactly the prefix that a site path drops."
  [url]
  (when (str/starts-with? url js/document.baseURI)
    (subs url (count js/document.baseURI))))

(defn ->route
  "The route of the site-relative `path`: the entry file basename it
  names and the sense in its fragment, an empty map for the front page,
  or nil for a path that is no page of the viewer."
  [path]
  (let [[path fragment] (str/split path #"#" 2)]
    (if (contains? #{"" "index.html"} path)
      {}
      (when-let [[_ file] (re-find #"^entry/([^/]+)/?$" path)]
        {:file file :sense (not-empty fragment)}))))

(defn ->reveal
  "The reveal instruction for a navigation to `sense` within `entry` of
  the shown group `entries`, told whether the previous location was the
  same entry (`same-entry?`) and the same homograph group (`same-group?`).

  The instruction names the node that takes the focus — the sense, or
  the entry when the entry carries no such sense — and what scrolls:
  the page top on arrival from another page at the first entry of the
  group, the entry when the target is the first sense of an entry
  arrived at from elsewhere, and otherwise the target itself."
  [entries {:keys [file senses]} sense same-entry? same-group?]
  (let [sense  (when (some (comp #{sense} :id) senses) sense)
        entry? (or (nil? sense)
                   (and (= sense (:id (first senses))) (not same-entry?)))
        top?   (and (= file (:file (first entries))) (not same-group?))]
    {:file   file
     :sense  sense
     :scroll (cond
               (not entry?) :sense
               top?         :top
               :else        :entry)}))

(defn update-title!
  "Set the document title from the current entry and the manifest title."
  []
  (let [{:keys [entries nav manifest]} @state
        headword (some #(when (= (:file nav) (:file %)) (:headword %)) entries)]
    (set! (.-title js/document)
          (str/join " – " (remove nil? [headword
                                        (or (:title manifest)
                                            views/fallback-title)])))))

(defonce sense-watch
  (atom nil))

(defonce settle-timer
  (atom nil))

(defn settle-scroll!
  "Quiet the sense watch through a navigation scroll, so the mark
  stays on the navigated sense instead of chasing the senses that
  stream past. Ticks resume once the scrolling has stayed idle for
  200 ms."
  []
  (some-> @settle-timer (js/clearTimeout))
  (reset! settle-timer (js/setTimeout #(reset! settle-timer nil) 200)))

(defn reading-line
  "How far down the viewport of height `vh` the reader reads, with
  `scrolled` pixels behind the viewport and `remaining` ones below it.

  The line rests a quarter down the viewport, where the reader's eyes
  rest. The closing screenful of a page can never be scrolled up to
  that line, so once the scroll starts to run out the line slides down
  to meet the content instead, and reaches the foot of the viewport as
  the page reaches its end. A page too short to scroll keeps its line
  where it started, since the reader has passed nothing."
  [vh scrolled remaining]
  (let [resting (* 0.25 vh)]
    (+ resting (min scrolled (max 0 (- vh resting remaining))))))

(defn current-sense
  "The sense the reader is on, from the ordered [id top] pairs `tops` of
  the sense elements and the reading-`line` they are measured against.

  The line is the whole of the rule, so it marks every sense it passes,
  and scrolling back up retraces them one by one in the order they
  arrived."
  [tops line]
  (or (last (for [[id top] tops :when (<= top line)] id))
      (ffirst tops)))

(defn unwatch-senses!
  "Remove the scroll watch of the senses, if one is active."
  []
  (when-let [stop! @sense-watch]
    (stop!)
    (reset! sense-watch nil)))

(defn watch-senses!
  "Follow the scroll with the :spy of the navigation state, the sense of
  `current-sense` over the sense elements of the shown entries, which
  the sense index and the margin mark render as the one on screen.

  Folding a sense moves the ones below it, so the watch ticks on the
  toggle of a details as well as on a scroll. While a navigation
  scroll settles (`settle-scroll!`), the watch stays quiet and the
  navigated sense keeps the mark."
  []
  (unwatch-senses!)
  (let [tick      (fn []
                    (let [vh        js/window.innerHeight
                          scrolled  js/window.scrollY
                          remaining (max 0 (- (.. js/document
                                                  -documentElement
                                                  -scrollHeight)
                                              vh scrolled))
                          tops      (mapv (fn [el]
                                            [(.-id el)
                                             (.-top (.getBoundingClientRect el))])
                                          (array-seq (js/document.querySelectorAll
                                                       ".sense[id]")))
                          line      (reading-line vh scrolled remaining)
                          spy       (current-sense tops line)]
                      (when (and spy (not= spy (:spy (:nav @state))))
                        (swap! state assoc-in [:nav :spy] spy))))
        on-scroll (fn []
                    (if @settle-timer
                      (settle-scroll!)
                      (tick)))
        ;; A toggle does not bubble, and the removal only matches a
        ;; listener registered with the same flag.
        capture   #js {:capture true}]
    (js/window.addEventListener "scroll" on-scroll #js {:passive true})
    (js/window.addEventListener "resize" on-scroll)
    (js/document.addEventListener "toggle" on-scroll capture)
    (reset! sense-watch (fn []
                          (js/window.removeEventListener "scroll" on-scroll)
                          (js/window.removeEventListener "resize" on-scroll)
                          (js/document.removeEventListener "toggle" on-scroll
                                                           capture)))
    (when-not @settle-timer (tick))))

(defn reveal!
  "Scroll to and focus the DOM `node` that the pending `reveal` named,
  then clear the instruction so that it runs once.

  The focus never scrolls on its own, so it cannot cut the smooth
  scroll short."
  [node {:keys [scroll] :as reveal}]
  (case scroll
    :top   (.scrollTo js/window 0 0)
    :entry (some-> (.closest node "article.entry")
                   (.scrollIntoView #js {:behavior "smooth"}))
    :sense (.scrollIntoView node #js {:behavior "smooth"}))
  (.focus node #js {:preventScroll true})
  (swap! state update :nav #(cond-> % (= reveal (:reveal %)) (dissoc :reveal))))

(defn show-entry!
  "Show the homograph group `entries` with the entry of the file
  basename `file` as its target and `sense` as its navigated sense.

  Where the reader came from decides what the reveal scrolls, and the
  state still holds the previous location, so both answers are read
  from it here rather than passed in."
  [entries file sense]
  (let [{:keys [nav] previous :raw-entries} @state
        entry       (first (filter #(= file (:file %)) entries))
        same-entry? (= file (:file nav))
        same-group? (boolean (some #(= file (:file %)) previous))]
    (swap! state (fn [state]
                   (-> state
                       (assoc :raw-entries entries :error nil
                              :query "" :active nil :routed? true)
                       (presentation/present-state)
                       (assoc :nav {:file    file
                                    :current sense
                                    :spy     (or sense (:id (first (:senses entry))))
                                    :reveal  (->reveal entries entry sense
                                                       same-entry? same-group?)}))))
    (update-title!)
    (settle-scroll!)
    (watch-senses!)))

(defn show-front!
  "Return to the front page and put the focus back in the search field."
  []
  (unwatch-senses!)
  (swap! state assoc :raw-entries nil :entries nil :nav {} :error nil
         :routed? true)
  (update-title!)
  (some-> (js/document.getElementById "search") (.focus)))

(defn route!
  "Show the homograph group of the entry of the current URL, or the
  front page. The fragment names a sense of the entry, which takes the
  focus and the on-screen mark.

  A group already on screen needs no fetch, so a link to another of its
  senses or entries resolves without a round trip."
  []
  (let [{:keys [file sense]} (->route (site-path js/location.href))
        {:keys [raw-entries]} @state]
    (cond
      (nil? file)
      (show-front!)

      (some #(= file (:file %)) raw-entries)
      (show-entry! raw-entries file sense)

      :else
      (-> (fetch-entry file)
          (.then (fn [entry]
                   (js/Promise.all
                     (into-array
                       (for [f (or (:homographs entry) [file])]
                         (if (= f file)
                           (js/Promise.resolve entry)
                           (fetch-entry f)))))))
          (.then (fn [entries]
                   (show-entry! (vec (array-seq entries)) file sense)))
          (.catch (fn [e]
                    (js/console.error e)
                    (swap! state assoc :raw-entries nil :entries nil :nav {}
                           :routed? true :error (.-message e))
                    (update-title!)))))))

(defn navigate!
  "Go to the site-relative `path` without leaving the page.

  Going where the reader already is replaces the history entry rather
  than stacking another one on it."
  [path]
  (if (= path (site-path js/location.href))
    (.replaceState js/history nil "" path)
    (.pushState js/history nil "" path))
  (route!))

(defn route-click!
  "Route the click `e` when it opens a page of this site in this tab,
  and leave every other click to the browser."
  [e]
  (when (and (zero? (.-button e))
             (not (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e))))
    (when-let [a (some-> (.-target e) (.closest "a[href]"))]
      (when-let [path (and (str/blank? (.-target a)) (site-path (.-href a)))]
        (when (->route path)
          (.preventDefault e)
          (navigate! path))))))

(defn next-active
  "The active result index after pressing `key` (\"ArrowDown\" or
  \"ArrowUp\") at index `active` among `n` results.

  Nil is the search field itself: Down enters the list at the top, Up
  leaves it there."
  [key active n]
  (case key
    "ArrowDown" (if active (min (inc active) (dec n)) 0)
    "ArrowUp"   (cond
                  (nil? active)  (dec n)
                  (zero? active) nil
                  :else          (dec active))))

(defn set-active!
  "Set the active result index to `i` and scroll it into view."
  [i]
  (swap! state assoc :active i)
  (when i
    (some-> (js/document.getElementById (str "result-" i))
            (.scrollIntoView #js {:block "nearest"}))))

(defn search-keydown!
  "Handle the keydown `e` in the search field over the current results.

  The arrow keys move the active result, Enter follows it (or the first
  row, or goes home on a blank query), Escape clears the search."
  [e]
  (let [{:keys [index query active]} @state
        rows (when (and index (seq query)) (views/matches index query))]
    (case (.-key e)
      ("ArrowDown" "ArrowUp")
      (when (seq rows)
        (.preventDefault e)
        (set-active! (next-active (.-key e) active (count rows))))
      "Enter"
      (if (str/blank? query)
        (navigate! hiccup/front-path)
        (when-let [row (nth rows (or active 0) nil)]
          (navigate! (hiccup/entry-path (:file row)))))
      "Escape"
      (swap! state assoc :query "" :active nil)
      nil)))

(defn pref-key
  "The localStorage key of the preference `pref` for the dataset of
  `manifest`."
  [pref manifest]
  (str "dmlex-viewer:" pref ":" (or (:uri manifest) (:title manifest) "default")))

(defn read-pref
  "The stored value of the preference `pref` for the dataset of
  `manifest`, or nil when none is stored."
  [pref manifest]
  (try
    (js/localStorage.getItem (pref-key pref manifest))
    (catch :default _ nil)))

(defn set-pref!
  "Set the state key `k` to `v`, present the entries again under it and
  remember it as the preference `pref` for the current dataset."
  [k pref v]
  (swap! state #(-> % (assoc k v) (presentation/present-state)))
  (try
    (js/localStorage.setItem (pref-key pref (:manifest @state)) (str v))
    (catch :default _ nil)))

(defn interpolate
  "The `action` with the placeholders of the DOM event `e` filled in."
  [e action]
  (mapv (fn [x]
          (case x
            :event/target.value   (.. e -target -value)
            :event/target.checked (.. e -target -checked)
            x))
        action))

(defn execute!
  "Perform the `actions` of one event handler or life-cycle hook, over
  the `dom-event` or `node` that Replicant passed with them."
  [{:replicant/keys [dom-event node]} actions]
  (doseq [action actions]
    (let [[verb & args] (cond->> action dom-event (interpolate dom-event))]
      (case verb
        :app/assoc      (apply swap! state assoc args)
        :app/fold       (swap! state update :folded
                               (if (.. dom-event -target -open) disj conj)
                               (first args))
        :app/set-pref   (apply set-pref! args)
        :app/focus      (.focus node)
        :app/reveal     (reveal! node (first args))
        :search/keydown (search-keydown! dom-event)))))

(defn render!
  "Render the app into the page from the current state.

  Nothing renders before the first route and the manifest have
  arrived: the page already shows the pre-rendered version of this
  view, and an early render would replace it with a half-loaded one."
  []
  (let [state @state]
    (when (and (:routed? state) (or (:manifest state) (:error state)))
      (let [ui (shared/ui-table translations state)]
        (r/render (js/document.getElementById "app")
                  (views/app (assoc state :ui ui :languages ui-languages))
                  {:alias-data {:ui ui}})))))

(defn init
  "Start the app: install the dispatch, the render loop and the routing,
  then load the data files."
  []
  (r/set-dispatch! execute!)
  (add-watch state ::render (fn [_ _ _ _] (render!)))
  (js/document.body.addEventListener "click" route-click!)
  (js/window.addEventListener "popstate" (fn [_] (route!)))
  (fetch-json! "data/manifest.json"
               (fn [{:keys [langCode] :as manifest}]
                 (swap! state
                        (fn [state]
                          (reduce (fn [state [k pref parse]]
                                    (if-let [stored (read-pref pref manifest)]
                                      (assoc state k (parse stored))
                                      state))
                                  (assoc state :manifest manifest)
                                  [[:lang "lang" identity]
                                   [:alpha? "alpha" #(= % "true")]
                                   [:presentation? "custom" #(= % "true")]])))
                 (swap! state presentation/present-state)
                 (when langCode
                   (set! (.-lang js/document.documentElement) langCode))
                 (update-title!)))
  (load-index!)
  (load-presentation!)
  (route!))
