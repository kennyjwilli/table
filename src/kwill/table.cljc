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
  (let [data-count (count (:data table))
        pagination (-> table :state :pagination)
        {:keys [page-size]} pagination
        n (Math/ceil (/ data-count page-size))]
    n))

(defn next-page-index
  [table {:keys [page-op page-index]}]
  (let [max-pages (page-count table)
        max-idx (dec max-pages)
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
  [data {:keys [pagination]}]
  (let [{:keys [page-index page-size]
         :or   {page-index 0
                page-size  10}} pagination
        data' (into []
                (partition-all page-size)
                data)]
    (get data' page-index)))

(defn with-sorted-data
  [data {:keys [sorting column-id->column]}]
  (let [{:keys [column-id->sort-direction]} sorting
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
      data)))

(defn columns-filters-state
  []
  {})

(defn column-visibility-state
  []
  {})

(defn set-column-visibility!
  [*state {:keys [column-id visible?]}]
  (swap! *state assoc column-id visible?))

(defn set-column-filters-search!
  [*state {:keys [table column-id search-str]}]
  (swap! *state assoc-in [column-id :search-str] search-str))

(defn column-filters-search-str
  [column-filters {:keys [column-id]}]
  (get-in column-filters [column-id :search-str]))

(defn with-column-filters-data
  [data {:keys []}]
  data)

(defn search-matches?
  [text search-str]
  ;; Could copy https://github.com/kentcdodds/match-sorter#this-solution
  (str/includes? (str/lower-case (str text)) (str/lower-case (str search-str))))

(defn column-unique-values
  [table {:keys [column-id]}]
  (let [{:keys [rows]} table]
    (into #{}
      (for [{:keys [visible-cells]} rows
            {:keys [get-value column]} visible-cells
            :when (= column-id (:id column))]
        (get-value)))))

(defn with-row-data
  [data {:keys [flat-columns
                column-filters]}]
  (into []
    (comp
      (map-indexed
        (fn [row-idx data-row]
          (let [row-id (or row-idx)
                visible-cells (reduce
                                (fn [cells column]
                                  (let [col-id (column-id column)
                                        search-str (get-in column-filters [col-id :search-str])
                                        get-value #((:accessor column) data-row)
                                        cell {:id        (str row-id ":" col-id)
                                              :row       data-row
                                              :cell      (or (:cell column) (get-value))
                                              :get-value get-value
                                              :column    column}]

                                    (if (or
                                          (str/blank? search-str)
                                          (search-matches? (get-value) search-str))
                                      (conj cells cell)
                                      (reduced nil))))
                                [] flat-columns)]
            (when visible-cells
              {:id            row-id
               :visible-cells visible-cells}))))
      (filter some?))
    data))

(defn rows
  [argm]
  (let [{:keys [data
                column-id->column
                sorting
                pagination]} argm
        data'
        (cond-> data
          sorting (with-sorted-data {:sorting sorting :column-id->column column-id->column})
          true (with-row-data argm)
          ;; pagination must go last, else we just sort/filter/etc the paginatied data
          pagination (with-paginated-data {:pagination pagination}))]
    data'))

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
        column-id->column (into {} (map (juxt :id identity)) flat-columns)
        rows (rows {:data              data
                    :flat-columns      (filter :visible? flat-columns)
                    :column-id->column column-id->column
                    :sorting           sorting
                    :pagination        pagination
                    :column-filters    column-filters})]
    (assoc table-argm
      :columns columns
      :header-groups header-groups
      :flat-columns flat-columns
      :rows rows)))
