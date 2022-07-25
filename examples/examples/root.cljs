(ns examples.root
  (:require
    [examples.reagent]
    [reagent.dom :as dom]))

(defn init
  []
  (enable-console-print!)

  (dom/render [examples.reagent/Root] (.getElementById js/document "app")))

(defn on-reload [] (init))
