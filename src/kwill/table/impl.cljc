(ns kwill.table.impl
  (:require
    [clojure.string :as str]))

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
  )

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

(defn prep-column
  [{:keys [column sorting column-filters column-visibility]}]
  (let [id (column-id column)
        children-columns (:columns column)
        leaf? (not children-columns)
        visible? (or
                   (and (not leaf?) (some :visible? children-columns))
                   (and leaf? (if-some [v (get column-visibility id)]
                                v
                                (if-some [v (:default-visible? column)] v true))))
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

(defn search-matches?
  [text search-str]
  ;; Could copy https://github.com/kentcdodds/match-sorter#this-solution
  (str/includes? (str/lower-case (str text)) (str/lower-case (str search-str))))

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
                            :data      data-row
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
                  (cond->
                    (-> acc
                      (update :cells-raw conj cell)
                      (update :column-id->cell assoc col-id cell)
                      (assoc
                        :visible? (if (false? (:visible? acc))
                                    false
                                    cell-visible?)))
                    (:visible? column)
                    (update :cells-visible conj cell))))
              {:id              row-id
               :data            data-row
               :visible?        true
               :cells-visible   []
               :cells-raw       []
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
