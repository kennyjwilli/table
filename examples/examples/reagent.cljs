(ns examples.reagent
  (:require
    [examples.datagen :as datagen]
    [kwill.table :as table]
    [reagent.core :as r]))

(defn render
  [renderable ctx]
  (if (fn? renderable)
    (renderable ctx)
    (str renderable)))

(defonce example-data (datagen/make-data {:size 100 :seed 1}))

(comment
  (table/table {:columns table/example-columns
                :data    example-data})
  )

(defn SortControls
  [{:keys [sort-direction toggle-sorting]}]
  [:div.flex.flex-col.justify-center.cursor-pointer
   {:on-click toggle-sorting}
   [:span {:class ["text-xs" "select-none"
                   (when (= :asc sort-direction)
                     "text-blue-400")]} "↑"]
   [:span {:class ["text-xs" "select-none"
                   (when (= :desc sort-direction)
                     "text-blue-400")]} "↓"]])

(defn Pagination
  [{:keys [page-sizes
           data-count
           page-data-range
           on-page-change
           page-op-available?
           on-change-page-size]}]
  [:div {:class ["flex" "gap-x-4"]}
   (into [:div {:class ["flex" "gap-x-2"]}]
     (map (fn [{:keys [page-op label]}]
            (let [disabled? (not (page-op-available? {:page-op page-op}))]
              [:button {:class    ["border" "disabled:opacity-50" "disabled:cursor-not-allowed"]
                        :disabled disabled?
                        :on-click (fn [_] (on-page-change {:page-op page-op}))}
               label])))
     [{:page-op :first
       :label   "<<"}
      {:page-op :previous
       :label   "<"}
      {:page-op :next
       :label   ">"}
      {:page-op :last
       :label   ">>"}])
   [:div
    [:label "Rows per page: "]
    [:select {:on-change (fn [e]
                           (on-change-page-size (js/parseInt (.. e -target -value))))}
     (map (fn [page-size]
            [:option {:key   page-size
                      :value page-size}
             page-size]) page-sizes)]]
   [:div
    [:span (first page-data-range)]
    [:span "-"]
    [:span (second page-data-range)]
    [:span " of "]
    [:span data-count]]])

(defn ColumnVisibilityControl
  [{:keys [columns on-column-visibility-change]}]
  [:div {:class ["inline-block" "border" "border-black" "rounded"]}
   [:div {:class ["border-b" "border-black"]}
    [:label
     [:input {:type "checkbox"}]
     "Toggle All"]]
   (map
     (fn [column]
       [:div {:key (:id column)}
        [:label
         [:input {:type      "checkbox"
                  :checked   (:visible? column)
                  :on-change (fn [event]
                               (on-column-visibility-change
                                 {:column-id (:id column)
                                  :visible?  (.. event -target -checked)}))}]
         (:id column)]])
     columns)])

(defn BasicTable
  []
  )

(defn GrandTable
  []
  )

;; TODO:
;;  - filters
;;  -

(defn Root
  []
  [:div
   (r/with-let [*sorting (r/atom (table/sorting-state))
                *pagination (r/atom (table/pagination-state))
                *column-filters (r/atom (table/columns-filters-state))
                *column-visibility (r/atom (table/column-visibility-state))]
     (let [table (table/table {:columns table/example-columns
                               :data    example-data
                               :state   {:sorting           @*sorting
                                         :pagination        @*pagination
                                         :column-filters    @*column-filters
                                         :column-visibility @*column-visibility}})
           column-filters @*column-filters]
       [:div
        [ColumnVisibilityControl
         {:columns                     (:flat-columns table)
          :on-column-visibility-change (fn [set-argm]
                                         (table/set-column-visibility! *column-visibility set-argm))}]
        [:table {:class ["min-w-full"]}

         [:thead {:class ["border-b" "bg-gray-50"]}
          (map
            (fn [header-group]
              [:tr {:key (:id header-group)}
               (map (fn [header]
                      [:th {:key     (:id header)
                            :colSpan (:colSpan header)
                            :class   ["text-sm" "font-medium" "text-gray-900px-6" "py-4" "px-2" "text-left" "border-r"]}
                       (when-not (:placeholder? header)
                         [:div.flex.justify-center.items-center
                          ;; The header content
                          [:div.flex-1 (render (:header header) header)]

                          ;; Sort controls
                          (when (:sortable? header)
                            [SortControls {:sort-direction (:sort-direction header)
                                           :toggle-sorting (fn [_]
                                                             (table/column-toggle-sorting! *sorting
                                                               {:column-id (:id header)}))}])

                          ;; Filter
                          (when (:filterable? header)
                            [:input {:type      "text"
                                     :value     (table/column-filters-search-str column-filters {:column-id (:id header)})
                                     :on-change (fn [event]
                                                  (table/set-column-filters-search! *column-filters
                                                    {:table      table
                                                     :column-id  (:id header)
                                                     :search-str (.. event -target -value)}))}])
                          ])])
                 (:headers header-group))])
            (:header-groups table))]

         [:tbody
          (map (fn [row]
                 [:tr {:key (:id row)}
                  (map
                    (fn [cell]
                      [:td {:key (:id cell)} (render (:cell cell) cell)])
                    (:visible-cells row))])
            (:rows table))]]
        [Pagination {:page-size           (:page-size @*pagination)
                     :page-sizes          [10 20 50 100]
                     :data-count          (count example-data)
                     :page-data-range     (let [{:keys [page-index page-size]} @*pagination
                                                start (* page-index page-size)
                                                end (+ start page-size)]
                                            [(inc start) end])
                     :page-op-available?  #(table/page-op-available? table %1)
                     :on-page-change      (fn [{:keys [page-op]}]
                                            (table/pagination-set-page-index! *pagination {:page-op page-op :table table}))
                     :on-change-page-size (fn [page-size]
                                            (table/pagination-set-page-size! *pagination {:page-size page-size}))}]]))])
