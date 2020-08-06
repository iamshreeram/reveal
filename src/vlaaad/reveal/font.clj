(ns vlaaad.reveal.font
  (:require [clojure.java.io :as io]
            [vlaaad.reveal.prefs :as prefs])
  (:import [com.sun.javafx.tk Toolkit]
           [com.sun.javafx.font PGFont FontResource]
           [com.sun.javafx.geom.transform BaseTransform]
           [javafx.scene.text Font]))

(set! *warn-on-reflection* true)

(set! *unchecked-math* :warn-on-boxed)

(defmacro ^:private if-class [class-name then else]
  `(try
     (Class/forName ^String ~class-name)
     ~then
     (catch ClassNotFoundException _#
       ~else)))

(def get-native-font
  (if-class "com.sun.javafx.scene.text.FontHelper"
    (let [meth (-> (Class/forName "com.sun.javafx.scene.text.FontHelper")
                   (.getDeclaredMethod "getNativeFont" (into-array Class [Font])))]
      #(.invoke meth nil (into-array Object [%])))
    (let [meth (-> (Class/forName "javafx.scene.text.Font")
                   (.getDeclaredMethod "impl_getNativeFont" (into-array Class [])))]
      #(.invoke meth % (into-array Object [])))))

(def ^Font font
  (let [[kind id] (:font-family prefs/prefs [:default "vlaaad/reveal/FantasqueSansMono-Regular.ttf"])
        size (double (:font-size prefs/prefs 14.5))]
    (case kind
      :default (Font/loadFont (io/input-stream (io/resource id)) size)
      :system-font (Font/font id size)
      :url-string (Font/loadFont ^String id size))))

(let [metrics (.getFontMetrics (.getFontLoader (Toolkit/getToolkit)) font)]
  (def ^double ^:const line-height (Math/ceil (.getLineHeight metrics)))
  (def ^double ^:const descent (.getDescent metrics)))

(def ^double ^:const char-width
  (-> font
      ^PGFont get-native-font
      (.getStrike BaseTransform/IDENTITY_TRANSFORM FontResource/AA_GREYSCALE)
      (.getCharAdvance \a)))