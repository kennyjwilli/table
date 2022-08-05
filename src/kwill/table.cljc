(ns kwill.table
  (:require
    [clojure.string :as str]
    [kwill.table.impl :as impl]))

(defn sorting-state
  []
  {:column-id-sort-order      []
   :column-id->sort-direction {}})

(defn column-toggle-sorting!
  [*state {:keys [column-id sort-direction]}]
  (swap! *state
    (fn [sorting]
      (let [current-sort-direction (get-in sorting [:column-id->sort-direction column-id])]
        {:column-id->sort-direction
         {column-id (or
                      sort-direction
                      (case current-sort-direction
                        :asc :desc
                        :desc :unsorted
                        :asc))}
         ;; unsupported for now...
         :column-id-sort-order [column-id]}))))

(def default-page-size 10)

(defn pagination-state
  []
  {:page-index 0
   :page-size  default-page-size})

(defn page-count
  [table]
  (let [data-count (count (:rows-visible table))
        pagination (-> table :state :pagination)
        {:keys [page-size]} pagination
        n (Math/ceil (/ data-count page-size))]
    n))

(defn next-page-index
  [table {:keys [page-op page-index]}]
  (let [max-pages (page-count table)
        max-idx (max 0 (dec max-pages))
        next-idx (case page-op
                   :first 0
                   :previous (dec page-index)
                   :next (inc page-index)
                   :last max-idx)]
    (min (max 0 next-idx) max-idx)))

(defn page-op-available?
  [table {:keys [page-op]}]
  (let [page-index (-> table :state :pagination :page-index)
        next-index (next-page-index table {:page-op page-op :page-index page-index})]
    (not= page-index next-index)))

(defn pagination-set-page-size!
  [*state {:keys [page-size]}]
  (swap! *state assoc :page-size page-size))

(defn pagination-set-page-index!
  [*state {:keys [page-op table]}]
  (swap! *state
    (fn [{:keys [page-index] :as pagination}]
      (let [next-idx (next-page-index table {:page-op page-op :page-index page-index})]
        (assoc pagination :page-index next-idx)))))

(defn columns-filters-state
  []
  {})

(defn column-visibility-state
  []
  {})

(defn set-column-visibility!
  [*state {:keys [column-id visible?]}]
  (swap! *state assoc column-id visible?))

(defn set-column-filters-search-str!
  [*state {:keys [column-id search-str]}]
  (swap! *state assoc-in [column-id :search-str] search-str))

(defn column-filters-search-str
  [column-filters {:keys [column-id]}]
  (get-in column-filters [column-id :search-str]))

(defn set-column-filters-value-set!
  [*state {:keys [column-id values]}]
  (swap! *state assoc-in [column-id :value-set] (some-> values set)))

(defn column-filtered?
  "Returns true if `column-id` is currently filtered."
  [column-filters {:keys [column-id]}]
  (let [{:keys [search-str value-set]} (get column-filters column-id)]
    (or
      (not (str/blank? search-str))
      value-set)))

(defn column-unique-values
  "Returns a map of unique column value data."
  [table {:keys [column-id]}]
  (let [{:keys [rows-raw]} table]
    (reduce
      (fn [acc value]
        (-> acc
          (update :values conj value)
          (update-in [:value->count value] (fnil inc 0))))
      {:values       #{}
       :value->count {}}
      (for [{:keys [column-id->cell]} rows-raw
            :let [{:keys [get-value]} (get column-id->cell column-id)]]
        (get-value)))))

(defn table
  [table-argm]
  (let [{raw-columns :columns
         :keys       [state data]} table-argm
        {:keys [sorting
                pagination
                column-filters
                column-visibility]} state
        columns (impl/walk-columns #(impl/prep-column
                                      {:column            %
                                       :sorting           sorting
                                       :column-filters    column-filters
                                       :column-visibility column-visibility})
                  raw-columns)
        header-groups (impl/header-groups {:columns columns})
        flat-columns (into [] (mapcat impl/find-leaves) columns)
        ;column-id->column (into {} (map (juxt :id identity)) flat-columns)
        {:keys [rows rows-raw rows-visible]}
        (impl/rows {:data           data
                    :flat-columns   (filter :visible? flat-columns)
                    :sorting        sorting
                    :pagination     pagination
                    :column-filters column-filters})]
    (assoc table-argm
      :columns columns
      ;; Table leaf columns -- no hierarchy.
      :flat-columns flat-columns
      ;; Header
      :header-groups header-groups
      ;; final rows presented to the user, including pagination
      :rows rows
      ;; Row data before any processing has occurred
      :rows-raw rows-raw
      ;; All rows that could be visible to the user, unpaginated
      :rows-visible rows-visible)))
