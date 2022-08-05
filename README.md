# table
Headless Clojure(Script) UI for building tables, heavily inspired by [TanStack Table v8](https://github.com/TanStack/table).

## Usage

```clojure
dev.kwill/table {:mvn/version "0.1.14"}
```

See `examples/examples/reagent.cljs` for an example of how to use the library with [Reagent](https://github.com/reagent-project/reagent). 

## API 
### `table`
Returns a [`Table`](#Table) that contains the necessary information to render a table UI element. 

### `column-unique-values`
Returns information about unique data in the table. Useful for displaying lists of filterable values. 

### `column-filtered?` 
Returns `true` if the column is currently filtered.

### 

## Model

### Table 
- `columns`: Seq of, potentially nested, [Columns](#Column). 
- `flat-columns`: Seq of leaf [Columns](#Column).
- `header-groups`: Seq of [Header Groups](#Header-Group)
- `rows`: Seq of [Rows](#Row) that are presented to the user (i.e., includes pagination if applicable)
- `rows-visible`: Seq of all [Rows](#Row) the user could see (i.e., unfiltered).
- `rows-raw`:  Seq of all [Rows](#Row) without any features enabled.
### Column
A column represents one or more columns rendered in the table. Columns with children (nesting) get grouped together under the parent. A column without children is a leaf. Column data is either user controlled (i.e., the user passed in the data) or computed (i.e., the library enriches the data). 
Any additional, user controlled keys can be added to the column map. To remove potential for conflict, it is recommended to use qualified keywords.
#### User controlled
- `header`: String or `(fn [header-group]) => Renderable` to display the header UI. If non-string, an `:id` is required to uniquely identifier the header.
- `accessor`: Function of an input data to the value used to render the [Cell](#Cell).
- `cell`: Optional value that can be used to render the cell. If unset, defaults to the value returned from calling `accessor`. 
- `id`: Optional value to uniquely identify this column. The value must be unique across **all columns**. If unset, a default id is computed the column `:header` if it's a string, else the `:accessor` if it's a string or keyword, otherwise an exception will be thrown. 
- `columns`: Optional seq of [Columns](#Column). Nested columns imply multiple header rows.
#### Computed 
- `visible?`: true if the column is currently visible, else false. 
- `column-leaves`: Optional seq of [Columns](#Column), present when the column has children. 
- `sortable?`: True if the column can be sorted. Only leaf columns are sortable. 
- `filterable?`: True if column filtering is enabled and if the column can be sorted (i.e., is a leaf). 
- `sort-direction`: Optional enum set to either `:asc` or `:desc` when the column is sorted, else unset or `nil`.

### Row
- `id`: A unique identifier for the table row.
- `data`: The data input map used to create the row.
- `visible?`: True if the row is visible to the user. For example, the row has not been filtered out. 
- `column-id->cell`. Lookup map keyed by the [Column](#Column) id to the [Cell](#Cell).

### Cell
An individual cell within the table. 
- `id`: A unique identifier for the table cell. 
- `data`: The data input map used to create the row.
- `cell`: Either the value passed in the [Column](#Column) or the value returned from calling `accessor`.
- `get-value`: No arg function returning the value returned from calling `accessor`. 
- `column`: The [Column](#Column) the cell belongs to.

### Header Group
Represents a row of header cells.
- `headers`: Seq of [Headers](#Header). 

### Header
Represents a single header cell. Builds upon the data present in a [Column](#Column).
- `colSpan`: Number of columns a cell should span. Typically passed directly to the [colspan](https://developer.mozilla.org/en-US/docs/Web/HTML/Element/td#attr-colspan) HTML property.
- `placeholder?`: True when the header represents an "invisible" column created to align nested columns. These header columns (e.g., `th`) should be rendered, but no content should be present.
### Renderable
An object that the code rendering the UI can use to display the component (e.g., for Reagent, this might be a hiccup vector or simply a string).