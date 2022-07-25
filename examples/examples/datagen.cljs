(ns examples.datagen
  (:require
    ["@faker-js/faker" :as faker]))

(defn new-person
  []
  {:first-name (.. faker -faker -name firstName)
   :last-name  (.. faker -faker -name lastName)
   :age        (.. faker -faker -datatype (number 40))
   :visits     (.. faker -faker -datatype (number 1000))
   :progress   (.. faker -faker -datatype (number 100))
   :status     (aget (.. faker -faker -helpers (shuffle #js ["relationship" "complicated" "single"])) 0)})

(comment
  (new-person)
  )

(defn make-data
  [{:keys [size seed]}]
  (when seed (.. faker -faker (seed seed)))
  (into [] (map (fn [_] (new-person))) (range size)))

(comment (make-data {:size 2 :seed 1}))
