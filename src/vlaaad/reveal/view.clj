(ns vlaaad.reveal.view
  (:require [vlaaad.reveal.output-panel :as output-panel]
            [vlaaad.reveal.popup :as popup]
            [vlaaad.reveal.event :as event]
            [vlaaad.reveal.stream :as stream]
            [vlaaad.reveal.action :as action]
            vlaaad.reveal.doc
            [vlaaad.reveal.fx :as rfx]
            [cljfx.api :as fx]
            [cljfx.prop :as fx.prop]
            [cljfx.mutator :as fx.mutator]
            [cljfx.lifecycle :as fx.lifecycle]
            [cljfx.component :as fx.component])
  (:import [clojure.lang IRef]
           [java.util.concurrent ArrayBlockingQueue TimeUnit BlockingQueue]
           [javafx.scene.control TableView TablePosition]
           [javafx.scene Node]
           [javafx.css PseudoClass]
           [java.net URL URI]
           [javafx.event Event]
           [javafx.scene.paint Color]))

(defn- runduce!
  ([xf x]
   (runduce! xf identity x))
  ([xf f x]
   (let [rf (xf (completing #(f %2)))]
     (rf (rf nil x)))))

(defmethod event/handle ::dispose-state [{:keys [id]}]
  #(dissoc % id))

(defmethod event/handle ::create-view-state [{:keys [id state]}]
  #(assoc % id (assoc state :id id)))

(defn- process-queue! [id ^BlockingQueue queue handler]
  (handler {::event/type ::create-view-state :id id :state (output-panel/make)})
  (let [*running (volatile! true)
        add-lines! #(handler {::event/type ::output-panel/on-add-lines :id id :fx/event %})
        xform (comp stream/stream-xf
                    (partition-all 128)
                    (take-while (fn [_] @*running)))
        f (event/daemon-future
            (while @*running
              (let [x (.take queue)]
                (runduce! xform add-lines! ({::nil nil} x x)))))]
    #(do
       (handler {::event/type ::dispose-state :id id})
       (future-cancel f)
       (vreset! *running false))))

(defn queue [{:keys [^BlockingQueue queue id]
              :or {id ::rfx/undefined}}]
  {:fx/type rfx/ext-with-process
   :id id
   :start process-queue!
   :args queue
   :desc {:fx/type output-panel/view}})

(defprotocol Viewable
  :extend-via-metadata true
  (make [this] "Returns cljfx description for the viewable"))

(defn- process-value! [id value handler]
  (handler {::event/type ::create-view-state :id id :state (output-panel/make {:autoscroll false})})
  (let [*running (volatile! true)
        add-lines! #(handler {::event/type ::output-panel/on-add-lines :id id :fx/event %})
        xform (comp stream/stream-xf
                    (partition-all 128)
                    (take-while (fn [_] @*running)))]
    (event/daemon-future
      (runduce! xform add-lines! value))
    #(do
       (vreset! *running false)
       (handler {::event/type ::dispose-state :id id}))))

(defn value [{:keys [value]}]
  {:fx/type rfx/ext-with-process
   :start process-value!
   :args value
   :desc {:fx/type output-panel/view}})

(defn- watch! [id *ref handler]
  (handler {::event/type ::create-view-state :id id :state (output-panel/make {:autoscroll false})})
  (let [*running (volatile! true)
        out-queue (ArrayBlockingQueue. 1024)
        submit! #(.put out-queue ({nil ::nil} % %))
        watch-key (gensym "vlaaad.reveal.view/watcher")
        f (event/daemon-future
            (while @*running
              (when-some [x (loop [x (.poll out-queue 1 TimeUnit/SECONDS)
                                   found nil]
                              (if (some? x)
                                (recur (.poll out-queue) x)
                                found))]
                (handler {::event/type ::output-panel/on-clear-lines :id id})
                (runduce! (comp stream/stream-xf
                                (partition-all 128)
                                (take-while
                                  (fn [_] (and @*running
                                               (nil? (.peek out-queue))))))
                          #(handler {::event/type ::output-panel/on-add-lines
                                     :fx/event %
                                     :id id})
                          ({::nil nil} x x)))))]
    (submit! @*ref)
    (add-watch *ref watch-key #(submit! %4))
    #(do
       (remove-watch *ref watch-key)
       (vreset! *running false)
       (future-cancel f)
       (handler {::event/type ::dispose-state :id id}))))

(defn ref-watch-latest [{:keys [ref]}]
  {:fx/type rfx/ext-with-process
   :start watch!
   :args ref
   :desc {:fx/type output-panel/view}})

(defn as-is [desc]
  (reify Viewable (make [_] desc)))

(action/defaction ::view [v]
  (when (or (instance? (:on-interface Viewable) v)
            (`make (meta v)))
    (constantly v)))

(action/defaction ::watch:latest [v]
  (when (instance? IRef v)
    #(as-is {:fx/type ref-watch-latest :ref v})))

(defn- log! [id *ref handler]
  (handler {::event/type ::create-view-state :id id :state (output-panel/make)})
  (let [*running (volatile! true)
        out-queue (ArrayBlockingQueue. 1024)
        submit! #(.put out-queue ({nil ::nil} % %))
        watch-key (gensym "vlaaad.reveal.view/watcher")
        *counter (volatile! -1)
        f (event/daemon-future
            (while @*running
              (let [x (.take out-queue)]
                (runduce!
                  (comp stream/stream-xf
                        (partition-all 128)
                        (take-while
                          (fn [_] @*running)))
                  #(handler {::event/type ::output-panel/on-add-lines
                             :fx/event %
                             :id id})
                  (stream/as-is
                    (stream/horizontal
                      (stream/raw-string (format "%4d: " (vswap! *counter inc))
                                         {:fill :util})
                      (stream/stream ({::nil nil} x x))))))))]
    (submit! @*ref)
    (add-watch *ref watch-key #(submit! %4))
    #(do
       (remove-watch *ref watch-key)
       (vreset! *running false)
       (future-cancel f)
       (handler {::event/type ::dispose-state :id id}))))

(defn ref-watch-all [{:keys [ref]}]
  {:fx/type rfx/ext-with-process
   :start log!
   :args ref
   :desc {:fx/type output-panel/view}})

(action/defaction ::watch:all [v]
  (when (instance? IRef v)
    #(as-is {:fx/type ref-watch-all :ref v})))

(defn- deref! [id blocking-deref handler]
  (handler {::event/type ::create-view-state :id id :state {:state ::waiting}})
  (let [f (event/daemon-future
            (try
              (handler {::event/type ::create-view-state
                        :id id
                        :state {:state ::value :value @blocking-deref}})
              (catch Throwable e
                (handler {::event/type ::create-view-state
                          :id id
                          :state {:state ::exception :exception e}}))))]
    #(do
       (future-cancel f)
       (handler {::event/type ::dispose-state :id id}))))

(defn- blocking-deref-view [{:keys [state] :as props}]
  (case state
    ::waiting {:fx/type :label
               :focus-traversable true
               :text "Loading..."}
    ::value (make (:value props))
    ::exception (make (:exception props))))

(defn derefable [{:keys [derefable]}]
  {:fx/type rfx/ext-with-process
   :start deref!
   :args derefable
   :desc {:fx/type blocking-deref-view}})

(extend-protocol Viewable
  nil
  (make [this] {:fx/type value :value this})

  Object
  (make [this] {:fx/type value :value this}))

(defn summary [{:keys [value max-length]
                :or {max-length 48}}]
  {:fx/type :group
   :children [(stream/fx-summary max-length value)]})

(defn- describe-cell [x]
  {:content-display :graphic-only
   :style-class "reveal-table-cell"
   :graphic {:fx/type summary :value x}})

(defn- initialize-table! [^TableView view]
  (.selectFirst (.getSelectionModel view))
  (.setCellSelectionEnabled (.getSelectionModel view) true))

(defn- select-bounds-and-value! [^Event event]
  (let [^TableView view (.getSource event)]
    (when-let [^TablePosition pos (first (.getSelectedCells (.getSelectionModel view)))]
      (when-let [cell (->> (.lookupAll view ".reveal-table-cell:selected")
                           (some #(when (contains? (.getPseudoClassStates ^Node %)
                                                   (PseudoClass/getPseudoClass "selected"))
                                    %)))]
        {:bounds (.localToScreen cell (.getBoundsInLocal cell))
         :value (.getCellData (.getTableColumn pos) (.getRow pos))}))))

(defn table [{:keys [items columns]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created initialize-table!
   :desc {:fx/type popup/ext
          :select select-bounds-and-value!
          :desc {:fx/type :table-view
                 :style-class "reveal-table"
                 :columns (for [{:keys [header fn]
                                 :or {header ::not-found}} columns]
                            {:fx/type :table-column
                             :style-class "reveal-table-column"
                             :min-width 40
                             :graphic {:fx/type summary
                                       :value (if (= header ::not-found) fn header)}
                             :cell-factory {:fx/cell-type :table-cell
                                            :describe describe-cell}
                             :cell-value-factory #(try (fn (peek %)) (catch Throwable e e))})
                 :items (into [] (map-indexed vector) items)}}})

(action/defaction ::view:table [v]
  (when (and (some? v)
             (not (string? v))
             (seqable? v))
    (fn []
      (let [head (first v)]
        (as-is {:fx/type table
                :items v
                :columns (cond
                           (map? head)
                           (for [k (keys head)]
                             {:header k :fn #(get % k)})

                           (map-entry? head)
                           [{:header 'key :fn key} {:header 'val :fn val}]

                           (indexed? head)
                           (for [i (range (count head))]
                             {:header i :fn #(nth % i)})

                           :else
                           [{:header 'item :fn identity}])})))))

(action/defaction ::browse:internal [v]
  (when (or (and (instance? URI v)
                 (or (#{"http" "https"} (.getScheme ^URI v))
                     (and (= "file" (.getScheme ^URI v))
                          (.endsWith (.getPath ^URI v) ".html"))))
            (instance? URL v)
            (and (string? v) (re-matches #"^https?://.+" v)))
    #(as-is {:fx/type :web-view
             :url (str v)})))

(defn- request-source-focus! [^Event e]
  (.requestFocus ^Node (.getSource e)))

(defn- labeled->values [labeled]
  (cond-> labeled (map? labeled) vals))

(defn- labeled->label+values [labeled]
  (cond
    (map? labeled) labeled
    (set? labeled) (map vector labeled labeled)
    :else (map-indexed vector labeled)))

(defn- labeled?
  "Check if every value in a coll of specified size has uniquely identifying label"
  [x pred & {:keys [min max]
             :or {min 1 max 32}}]
  (and (or (map? x)
           (set? x)
           (sequential? x))
       (<= min (bounded-count (inc max) x) max)
       (every? pred (labeled->values x))))

(defn pie-chart [{:keys [data]}]
  {:fx/type :pie-chart
   :style-class "reveal-chart"
   :on-mouse-pressed request-source-focus!
   :animated false
   :data (for [[k v] (labeled->label+values data)]
           {:fx/type :pie-chart-data
            :name (stream/str-summary k)
            :pie-value v})})

(action/defaction ::view:pie-chart [x]
  (when (labeled? x number? :min 2)
    #(as-is {:fx/type pie-chart :data x})))

(def ^:private ext-with-value-on-node
  (fx/make-ext-with-props
    {::value (fx.prop/make
               (fx.mutator/setter (fn [^Node node value]
                                    (if (some? value)
                                      (.put (.getProperties node) ::value value)
                                      (.remove (.getProperties node) ::value))))
               fx.lifecycle/scalar)}))

(defn- select-chart-node! [^Event event]
  (let [^Node node (.getTarget event)]
    (when-let [value (::value (.getProperties node))]
      {:value value
       :bounds (.localToScreen node (.getBoundsInLocal node))})))

(defn- numbered? [x]
  (or (number? x)
      (and (vector? x)
           (= 2 (count x))
           (number? (x 0)))))

(defn- numbered->number [numbered]
  (cond-> numbered (not (number? numbered)) first))

(defn bar-chart [{:keys [data]}]
  {:fx/type popup/ext
   :select select-chart-node!
   :desc {:fx/type :bar-chart
          :style-class "reveal-chart"
          :on-mouse-pressed request-source-focus!
          :animated false
          :x-axis {:fx/type :category-axis :label "key"}
          :y-axis {:fx/type :number-axis :label "value"}
          :data (for [[series v] (labeled->label+values data)]
                  {:fx/type :xy-chart-series
                   :name (stream/str-summary series)
                   :data (for [[key value] (labeled->label+values v)]
                           {:fx/type :xy-chart-data
                            :x-value (stream/->str key)
                            :y-value (numbered->number value)
                            :node {:fx/type ext-with-value-on-node
                                   :props {::value {:value value
                                                    :key key
                                                    :series series}}
                                   :desc {:fx/type :region}}})})}})

(action/defaction ::view:bar-chart [x]
  (when-let [data (cond
                    (labeled? x numbered?)
                    {x x}

                    (labeled? x #(labeled? % numbered?))
                    x)]
    #(as-is {:fx/type bar-chart
             :data data})))

(defn- numbereds? [x]
  (and (sequential? x)
       (<= 2 (bounded-count 1025 x) 1024)
       (every? numbered? x)))

(defn line-chart [{:keys [data]}]
  {:fx/type popup/ext
   :select select-chart-node!
   :desc {:fx/type :line-chart
          :style-class "reveal-chart"
          :on-mouse-pressed request-source-focus!
          :animated false
          :x-axis {:fx/type :number-axis
                   :label "index"
                   :auto-ranging false
                   :lower-bound 0
                   :upper-bound (dec (transduce
                                       (comp (map second) (map count))
                                       max
                                       0
                                       (labeled->label+values data)))
                   :tick-unit 10
                   :minor-tick-count 10}
          :y-axis {:fx/type :number-axis
                   :label "value"
                   :force-zero-in-range false}
          :data (for [[series numbers] (labeled->label+values data)]
                  {:fx/type :xy-chart-series
                   :name (stream/str-summary series)
                   :data (->> numbers
                              (map-indexed
                                (fn [index value]
                                  {:fx/type :xy-chart-data
                                   :x-value index
                                   :y-value (numbered->number value)
                                   :node {:fx/type ext-with-value-on-node
                                          :props {::value {:value value
                                                           :index index
                                                           :series series}}
                                          :desc {:fx/type :region}}})))})}})

(action/defaction ::view:line-chart [x]
  (when-let [data (cond
                    (numbereds? x)
                    {x x}

                    (labeled? x numbereds?)
                    x)]
    #(as-is {:fx/type line-chart :data data})))

(defn- coordinate? [x]
  (and (sequential? x)
       (= 2 (bounded-count 3 x))
       (number? (nth x 0))
       (number? (nth x 1))))

(defn- scattered? [x]
  (or (coordinate? x)
      (and (vector? x)
           (= 2 (count x))
           (coordinate? (x 0)))))

(defn- scattered->coordinate [x]
  (let [f (first x)]
    (if (sequential? f) f x)))

(defn- scattereds? [x]
  (and (coll? x)
       (<= 1 (bounded-count 1025 x) 1024)
       (every? scattered? x)))

(defn scatter-chart [{:keys [data]}]
  {:fx/type popup/ext
   :select select-chart-node!
   :desc {:fx/type :scatter-chart
          :style-class "reveal-chart"
          :on-mouse-pressed request-source-focus!
          :animated false
          :x-axis {:fx/type :number-axis :label "x" :force-zero-in-range false}
          :y-axis {:fx/type :number-axis :label "y" :force-zero-in-range false}
          :data (for [[series places] (labeled->label+values data)]
                  {:fx/type :xy-chart-series
                   :name (stream/str-summary series)
                   :data (for [value places
                               :let [[x y :as с] (scattered->coordinate value)]]
                           {:fx/type :xy-chart-data
                            :x-value x
                            :y-value y
                            :node {:fx/type ext-with-value-on-node
                                   :props {::value {:value value
                                                    :coordinate с
                                                    :series series}}
                                   :desc {:fx/type :region}}})})}})

(action/defaction ::view:scatter-chart [x]
  (when-let [data (cond
                    (labeled? x scattereds?)
                    x

                    (scattereds? x)
                    {x x})]
    #(as-is {:fx/type scatter-chart :data data})))

(action/defaction ::view:color [v]
  (when-let [color (cond
                     (instance? Color v) v
                     (string? v) (Color/valueOf v)
                     (keyword? v) (Color/valueOf (name v)))]
    #(as-is {:fx/type :region
             :background {:fills [{:fill color}]}})))

(deftype Observable [*ref f]
  IRef
  (deref [_] (f @*ref))
  (addWatch [this key callback]
    (add-watch *ref [this key] #(callback key this (f %3) (f %4))))
  (removeWatch [this key]
    (remove-watch *ref [this key])))

(defn- observe! [id [ref fn] handler]
  (handler {::event/type ::create-view-state :id id :state {:desc (fn @ref)}})
  (let [watch-key (gensym "vlaaad.reveal.view/observable")]
    (add-watch ref watch-key #(handler {::event/type ::create-view-state :id id :state {:desc (fn %4)}}))
    #(do
       (remove-watch ref watch-key)
       (handler {::event/type ::dispose-state :id id}))))

(defn- desc-view [m]
  (:desc m))

(defn observable-view [{:keys [ref fn]}]
  {:fx/type rfx/ext-with-process
   :start observe!
   :args [ref fn]
   :desc {:fx/type desc-view}})

(def ext-try
  (reify fx.lifecycle/Lifecycle
    (create [_ {:keys [desc]} opts]
      (try
        (with-meta
          {:child (fx.lifecycle/create fx.lifecycle/dynamic desc opts)}
          {`fx.component/instance #(-> % :child fx.component/instance)})
        (catch Exception e
          (with-meta
            {:exception e
             :child (fx.lifecycle/create fx.lifecycle/dynamic {:fx/type value :value e} opts)}
            {`fx.component/instance #(-> % :child fx.component/instance)}))))
    (advance [_ component {:keys [desc]} opts]
      (if-let [e (:exception component)]
        (update component :child
                #(fx.lifecycle/advance fx.lifecycle/dynamic % {:fx/type value :value e} opts))
        (try
          (update component :child
                  #(fx.lifecycle/advance fx.lifecycle/dynamic % desc opts))
          (catch Exception e
            (assoc component :exception e
                             :child (fx.lifecycle/create fx.lifecycle/dynamic
                                                         {:fx/type value :value e}
                                                         opts))))))
    (delete [_ component opts]
      (fx.lifecycle/delete fx.lifecycle/dynamic (:child component) opts))))