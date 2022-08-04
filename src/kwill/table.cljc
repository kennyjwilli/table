(ns kwill.table
  (:require
    [clojure.string :as str]))

(def example-columns
  [{:header  "Name"
    :columns [{:accessor :first-name
               :header   "firstName"}
              {:accessor :last-name
               :header   "lastName"}]}
   {:header  "Info"
    :columns [{:accessor :age
               :header   (constantly "Age")}
              {:header  "More Info"
               :columns [{:accessor :visits
                          :header   "Visits"}
                         {:accessor :status
                          :header   "Status"}
                         {:accessor :progress
                          :header   "Profile Progress"}]}]}])

(defn walk-columns
  [f col-or-cols]
  (cond
    (sequential? col-or-cols)
    (into [] (map #(walk-columns f %)) col-or-cols)
    (map? col-or-cols)
    (f
      (if-let [children (seq (:columns col-or-cols))]
        (assoc col-or-cols :columns (walk-columns f children))
        col-or-cols))))

(comment (walk-columns #(doto % prn) example-columns))

(defn find-leaves
  [column]
  (if-let [children (:columns column)]
    (mapcat find-leaves children)
    [column]))

(comment (find-leaves (second example-columns)))

(defn find-inodes
  [column]
  (when-let [children (:columns column)]
    (into [column]
      (comp
        (keep #(find-inodes %))
        cat)
      children)))

(comment
  (find-inodes (second example-columns)))

(defn column-id
  [column]
  (or
    (:id column)
    (let [h (:header column)]
      (when (string? h) h))
    (let [a (:accessor column)]
      (when (or (string? a) (keyword? a)) a))
    (throw (ex-info "No column id defined for column." {:column column}))))

(comment
  [{:id      ""
    :headers [{:id      ""
               :colspan 1}]}]
  )

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

(defn header-groups
  [table]
  (let [{:keys [columns state]} table]
    (loop [columns columns
           acc []]
      (let [id (str/join ":" (map column-id columns))
            headers (keep (fn [column]
                            (when (:visible? column)
                              (assoc column
                                :colSpan (or (some-> column :column-leaves count) 1))))
                      columns)
            header-group {:id      id
                          :headers headers}
            child-columns (for [column columns
                                child-col (:columns column)]
                            child-col)
            ;; If children have children themselves, children without children
            ;; must be pushed down 1 level.
            children? (some :columns child-columns)
            children-columns' (cond->> child-columns
                                children?
                                (map (fn [column]
                                       (if (:columns column)
                                         column
                                         {:id           (column-id column)
                                          :visible?     true
                                          :placeholder? true
                                          :columns      [column]}))))
            ;; if no children are visible, don't include the header
            child-visible? (or (some :visible? child-columns) (not children?))
            acc' (cond-> acc
                   child-visible? (conj header-group))]
        (if (seq children-columns')
          (recur children-columns' acc')
          acc')))))

(defn prep-column
  [{:keys [column sorting column-filters column-visibility]}]
  (let [id (column-id column)
        children-columns (:columns column)
        leaf? (not children-columns)
        visible? (or
                   (and (not leaf?) (some :visible? children-columns))
                   (and leaf? (if-some [v (get column-visibility id)] v true)))
        leaves (when (:columns column) (find-leaves column))
        sort-direction (get-in sorting [:column-id->sort-direction id])]
    (-> column
      (assoc
        :id id
        :visible? visible?)
      (cond->
        leaves
        (assoc :column-leaves leaves)

        ;; Only leaf columns are sortable
        leaf?
        (assoc :sortable? true)

        (and column-filters leaf?)
        (assoc :filterable? true)

        sort-direction
        (assoc :sort-direction sort-direction)))))

(defn pagination-state
  []
  {:page-index 0
   :page-size  10})

(defn page-count
  [table]
  (let [data-count (count (:rows table))
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

(defn with-paginated-data
  [rows {:keys [pagination]}]
  (let [{:keys [page-index page-size]
         :or   {page-index 0
                page-size  10}} pagination
        data' (into []
                (comp
                  (filter :visible?)
                  (partition-all page-size))
                rows)]
    (get data' page-index)))

(defn with-sorted-data
  [rows {:keys [sorting]}]
  (let [{:keys [column-id->sort-direction]} sorting
        [column-id sort-direction] (first column-id->sort-direction)]
    (if (contains? #{:asc :desc} sort-direction)
      ;; TODO: implement :column-id-sort-order
      (sort-by
        (fn [row] ((get-in row [:column-id->cell column-id :get-value])))
        (case sort-direction
          :asc compare
          :desc #(compare %2 %1))
        rows)
      rows)))

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

(defn with-column-filters-data
  [data {:keys []}]
  data)

(defn search-matches?
  [text search-str]
  ;; Could copy https://github.com/kentcdodds/match-sorter#this-solution
  (str/includes? (str/lower-case (str text)) (str/lower-case (str search-str))))

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

(defn data->rows-state
  [data {:keys [flat-columns
                column-filters]}]
  (reduce
    (fn [acc data-row]
      (let [row-idx (:idx acc)
            row-id (or row-idx)
            row-map
            (reduce
              (fn [acc column]
                (let [col-id (column-id column)
                      {:keys [search-str value-set]} (get column-filters col-id)
                      get-value #((:accessor column) data-row)
                      cell {:id        (str row-id ":" col-id)
                            :row       data-row
                            :cell      (or (:cell column) (get-value))
                            :get-value get-value
                            :column    column}
                      cell-visible?
                      (and
                        ;; Text search
                        (or
                          (str/blank? search-str)
                          (search-matches? (get-value) search-str))
                        ;; Enum (value set) match
                        (or
                          ;; nil implies no filtering is active
                          (nil? value-set)
                          ;; if filtering is active, value-set must be non-nil & value must be in value-set
                          (and
                            value-set
                            (contains? value-set (get-value)))))]
                  (-> acc
                    (update :visible-cells conj cell)
                    (update :column-id->cell assoc col-id cell)
                    (assoc
                      :visible? (if (false? (:visible? acc))
                                  false
                                  cell-visible?)))))
              {:id              row-id
               :visible?        true
               :visible-cells   []
               :column-id->cell {}} flat-columns)]
        (cond-> (-> acc
                  (update :idx inc)
                  (update :rows-raw conj row-map))
          (:visible? row-map)
          (update :rows-visible conj row-map))))
    {:idx          0
     :rows-raw     []
     :rows-visible []}
    data))

(defn rows
  [argm]
  (let [{:keys [data
                sorting
                pagination]} argm
        {:keys [rows-raw rows-visible]} (data->rows-state data argm)
        rows-ret
        (cond-> rows-visible
          sorting (with-sorted-data {:sorting sorting})
          ;; pagination must go last, else we just sort/filter/etc the paginated data
          pagination (with-paginated-data {:pagination pagination}))]
    {:rows         rows-ret
     :rows-visible rows-visible
     :rows-raw     rows-raw}))

(comment
  (with-sorted-data data {:sorting sorting :column-id->column column-id->column})

  (let [sorting {:column-id->sort-direction {"Visits" :desc},
                 :column-id-sort-order      ["Visits"]}
        {:keys [column-id->sort-direction]} sorting
        [column-id sort-direction] (first column-id->sort-direction)
        accessor (get-in column-id->column [column-id :accessor])]
    (if (contains? #{:asc :desc} sort-direction)
      ;; TODO: implement :column-id-sort-order
      (sort-by
        (fn [row] (accessor row))
        (case sort-direction
          :asc compare
          :desc #(compare %2 %1))
        data)
      data))
  (require 'sc.api)

  (sort-by
    (fn [row] (:visits row))
    (case :desc
      :asc compare
      :desc #(compare %2 %1))
    data)

  (with-sorted-data data {:sorting
                          {:column-id->sort-direction {"Visits" :asc},
                           :column-id-sort-order      ["Visits"]}
                          :column-id->column column-id->column})

  (walk-columns #(prep-column {:column % :sorting {}})
    example-columns)
  )

(defn table
  [table-argm]
  (let [{raw-columns :columns
         :keys       [state data]} table-argm
        {:keys [sorting
                pagination
                column-filters
                column-visibility]} state
        columns (walk-columns #(prep-column
                                 {:column            %
                                  :sorting           sorting
                                  :column-filters    column-filters
                                  :column-visibility column-visibility})
                  raw-columns)
        header-groups (header-groups {:columns columns})
        flat-columns (into [] (mapcat find-leaves) columns)
        ;column-id->column (into {} (map (juxt :id identity)) flat-columns)
        {:keys [rows rows-raw rows-visible]}
        (rows {:data           data
               :flat-columns   (filter :visible? flat-columns)
               :sorting        sorting
               :pagination     pagination
               :column-filters column-filters})]
    (assoc table-argm
      :columns columns
      :header-groups header-groups
      :flat-columns flat-columns
      :rows rows
      :rows-raw rows-raw
      :rows-visible rows-visible)))
